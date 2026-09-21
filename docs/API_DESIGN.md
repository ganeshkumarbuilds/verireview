# VeriReview — API Design

> Base path: `/api/v1`. All responses use a common envelope. API docs via Springdoc OpenAPI (`/api-docs`, `/swagger-ui`).

## 1. Conventions

- RESTful nouns, plural: `/projects`, `/reviews`, `/findings`, `/fix-requests`, `/patches`, `/verification-runs`.
- DTOs only — JPA entities never serialized. Separate `*Request` (input, validated) and `*Response` (output) types; `*Mapper` converts.
- Auth: `Authorization: Bearer <accessJwt>`; refresh via `POST /auth/refresh` (rotation). 401 = missing/invalid token, 403 = valid token but not owner/admin.
- Pagination: `GET` lists accept `page (0-based), size (default 20, max 100), sort (field,dir), + filters`. Response: `{ content[], page, size, totalElements, totalPages }` (Spring `Page` mapped to DTO page).
- Envelope:
  ```json
  { "data": {}, "error": null, "meta": { "traceId": "..." } }
  // error case: { "data": null, "error": { "code": "FINDING_NOT_FOUND", "message": "...", "details": {} } }
  ```
- Error codes are stable strings (`PROJECT_NOT_FOUND`, `VALIDATION_FAILED`, `FORBIDDEN`, `RATE_LIMITED`, `JOB_FAILED`, ...); HTTP status + code + human message.
- Validation errors → `400` with field-level `details`. Bean Validation on every `@RequestBody`; path traversal / URL allowlists enforced in service layer, not just annotations.
- Idempotency for job-triggering POSTs: client may send `Idempotency-Key` header; server dedupes review/fix triggers per scope.
- Long jobs are async: `202 Accepted` + `{ jobId, statusUrl }`; client polls `statusUrl`. No endpoint blocks on LLM/build.

## 2. Endpoints (v1)

### Auth (`/auth`)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/auth/register` | Register (email, password ≥8 chars, displayName). Returns 201 + tokens. Rate-limited. |
| POST | `/auth/login` | Login → access + refresh tokens. Rate-limited + audit-logged. |
| POST | `/auth/refresh` | Rotate refresh → new pair. |
| POST | `/auth/logout` | Invalidate refresh (and audit). |
| POST | `/auth/password-reset/request` | Request a reset email (always 202, never reveals account existence; delivery pending mail integration). Rate-limited. |
| GET | `/auth/me` | Current user profile. |

### Users (`/users`) — ADMIN mostly

| Method | Path | Description |
|--------|------|-------------|
| GET | `/users/me/settings` | Get settings. |
| PATCH | `/users/me/settings` | Update settings (validated). |
| GET | `/users` | ADMIN list (paged). |

### Projects (`/projects`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/projects?search=&sourceType=&sort=` | List own projects (paged/filter/sort). ADMIN `?owner=` optional. |
| POST | `/projects` | Create project shell (name, description, language). Returns project. |
| GET | `/projects/{id}` | Detail + source metadata. Owner check. |
| PATCH | `/projects/{id}` | Rename/describe. |
| DELETE | `/projects/{id}` | Soft-delete (audit). |
| GET | `/projects/{id}/files?pathPrefix=&search=` | File tree/list (paged). |
| GET | `/projects/{id}/files/content?path=` | Single file content (size-capped, read-only). Path normalized + jailed. |
| POST | `/projects/import/zip` | Multipart ZIP upload (see §4). |
| POST | `/projects/import/paste` | `{ filename, language, content }` (capped). |
| POST | `/projects/import/github` | `{ repoUrl, branch? }` allowlisted to github.com, shallow. Async → 202. |

### Reviews & Findings

| Method | Path | Description |
|--------|------|-------------|
| POST | `/projects/{id}/reviews` | Trigger review → 202 + review id/statusUrl. One running review per project (409 if busy). |
| GET | `/projects/{id}/reviews` | Review history (paged, newest first). |
| GET | `/reviews/{reviewId}` | Review detail + status + duration + counts. |
| GET | `/reviews/{reviewId}/findings?severity=&category=&status=&sort=` | Findings (paged/filter/sort). |
| GET | `/findings/{findingId}` | Finding detail + evidence + linked fix/verification refs. |
| PATCH | `/findings/{findingId}` | Mark WONTFIX with reason (owner only; audit). No delete. |

### Fix & Patch

| Method | Path | Description |
|--------|------|-------------|
| POST | `/findings/{id}/fix-requests` | Create FixRequest (scope note) → 202. 409 if one already open for this finding. **This is the explicit approval gate.** |
| GET | `/findings/{id}/fix-requests` | Fix history for finding. |
| GET | `/fix-requests/{fixId}` | Detail + patch ref + status. |
| GET | `/fix-requests/{fixId}/patch` | Patch diff + stats (read-only). |
| POST | `/fix-requests/{fixId}/cancel` | Cancel pending/in-progress request. |

### Verification

| Method | Path | Description |
|--------|------|-------------|
| GET | `/patches/{patchId}/verification-runs` | Runs for patch (usually 1 + reruns). |
| GET | `/verification-runs/{runId}` | Detail: build status, test summary, deltas, verdict, log excerpts, duration. |
| GET | `/verification-runs/{runId}/tests?status=` | Per-test results (paged). |

### Generation (`/generations`)

Implemented contract (Generate workflow): the wizard collects requirement,
explicit stack, optional database config, and per-run AI credentials; the
backend queues the job and the frontend polls real state
`QUEUED → PLANNING → GENERATING → REVIEWING → COMPLETED / FAILED`.

| Method | Path | Description |
|--------|------|-------------|
| POST | `/generations` | `{ name, requirement, description?, backend, frontend, database, databaseConfig?, aiConfig, draft? }` → 201 + generation (`DRAFT` when `draft: true`, else `QUEUED`). Secrets (`databaseConfig.password`, `aiConfig.apiKey`) travel once, are held in memory only, and never appear in any response. |
| GET | `/generations` | List own generations (paged). Secrets masked (`passwordConfigured`, `keyConfigured` flags only). |
| GET | `/generations/{id}` | Generation state + masked config + `projectId` once `COMPLETED`. Owner only (else 404). Also carries `iteration`, `maxIterations`, `revisionNumber`, `revisionCount`, and nullable `artifact` facts (never storage paths). |
| POST | `/generations/{id}/revisions` | Append a task modification as a new numbered revision (drafts only, else 409). Body `{ requirement }`; response is the revision, never secrets. |
| GET | `/generations/{id}/revisions` | Ordered revision history for a generation. Owner only (else 404). |
| POST | `/generations/{id}/fix-requests` | Approval gate for the fix loop: creates one FixRequest per OPEN finding of the latest review (REVIEWED only, else 409). Body `{ scopeNote? }` → 202 `{ reviewId, created[], skippedOpen }`. Never mutates code. |
| POST | `/generations/{id}/rebuild` | Rebuild + reverify after an applied generation patch. Body `{ patchId }` (must be `APPLIED`) → 202. Backend owns the lifecycle state. |
| GET | `/generations/{id}/download` | Verified project ZIP. Served only when the backend quality gate passes (artifact + REVIEWED + VERIFIED verdict + SUCCESS build + completed review + zero blocking findings); else 409 with `X-Download-Blocked-Reason`. ZIP carries an `.env.example` with placeholders — never real secrets. |

Rules: explicit stack enums only (`JAVA_SPRING_BOOT`/`PYTHON_FASTAPI`/`NODEJS`, `REACT_TYPESCRIPT`/`NONE`, `POSTGRESQL`/`MYSQL`/`MONGODB`/`NONE`, `OPENROUTER`/`CUSTOM`/`NONE`); `databaseConfig` required iff database ≠ `NONE`; `baseUrl` required iff provider is `CUSTOM`. Provider `NONE` needs no key and takes no `apiKey`/`baseUrl` (model defaults to `template`): the backend builds a deterministic Java starter through the same build/verify/review/download gates (Java only in this phase). Generated code must use env-var placeholders — embedded secrets fail the run. Output becomes a normal `GENERATED` project consumable by the existing Project/Review flow. No sandbox execution in this phase.

### Agent executions & Audit (observability of the workflow)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/projects/{id}/agent-executions` | Timeline of agent runs (paged). Owner only. |
| GET | `/admin/audit-logs` | ADMIN: audit trail (paged/filter). |
| GET | `/actuator/health` | Liveness (public minimal); full Actuator behind ADMIN/secured port. |

## 3. DTO Sketch (Illustrative)

- `ProjectResponse { id, name, description, sourceType, language, status, createdAt, updatedAt }`
- `FindingResponse { id, reviewId, category, severity, source, status, title, description, filePath, lineStart, lineEnd, evidence, createdAt }`
- `PatchResponse { id, fixRequestId, diff, filesChanged, additions, deletions, status }`
- `VerificationRunResponse { id, patchId, buildStatus, testsTotal/Passed/Failed/Skipped, verdict, durationMs, createdAt }`

## 4. Upload / Import Rules (API-level)

- `multipart/form-data` with `file` (.zip), size ≤ 50 MB (configurable), MIME + magic-byte check, filename sanitized.
- ZIP entries capped (e.g. ≤2000 files, ≤200 MB uncompressed, no symlinks, no `..`, no absolute paths) — rejected with `VALIDATION_FAILED` + reason before any extraction.
- GitHub import: URL must match `https://github.com/<owner>/<repo>(.git)?`; branch name allowlisted charset; shallow clone `--depth 1`; repo size cap; private repos → 422 until OAuth phase.

## 5. Auth & RBAC Matrix (Summary)

| Action | USER (owner) | USER (non-owner) | ADMIN |
|--------|--------------|------------------|-------|
| Own projects/files/reviews/findings | full | 403/404* | read (audit/support) |
| Create fix request on own finding | yes | no | no (unless owner) |
| Mark WONTFIX | yes + reason | no | no |
| View audit logs | own actions only (if exposed) | no | full |
| Actuator full / metrics | no | no | yes |

*Non-owner access returns 404 (not 403) for project-scoped reads to avoid ID enumeration — decided Phase 3, recorded here as intent.

## 6. Rate Limiting (API-level intent; mechanism in Phase 19 / Redis trigger)

- Strict: `/auth/*`, import endpoints, review/fix triggers (per-user per-minute caps).
- Response on exceed: `429 + Retry-After`. Limits documented in OpenAPI descriptions.

## 7. Backend ↔ AI-Service Contract (Internal, Versioned)

Not exposed to browsers. Service-to-service auth (shared secret / mTLS — decided Phase 7; default: secret header + network isolation via Compose).

- `POST /internal/review { reviewId, files[], deterministicFindings[] } → { findings[] }`
- `POST /internal/plan { requirement, prefs } → { plan | needsClarification }`
- `POST /internal/code-fix { fixRequestId, finding, files[] } → { diff, explanation }`
- `POST /internal/verify { verificationRunId, evidence } → { recommendation, rationale }`

All schemas versioned (`v1` prefix in path); breaking changes require version bump + ADR. AI outputs re-validated by backend (size, paths, diff shape) before persistence.
