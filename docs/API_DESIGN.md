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
  // error case: { "data": null, "error": { "status": 404, "code": "FINDING_NOT_FOUND", "message": "...", "details": {} } }
  ```
  `error.status` mirrors the HTTP status so clients read the status, code, and plain-language message from one place. Messages are safe for direct display — never stack traces or internal paths.
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
| POST | `/patches/{patchId}/apply` | Apply a `PROPOSED` patch to its project/generation workspace. Snapshots the previous content of every touched file (revert evidence). 409 unless `PROPOSED`. |
| POST | `/patches/{patchId}/revert` | Restore the previous file content from the snapshots taken at apply time; patch becomes `REVERTED`. 409 unless `APPLIED` and a snapshot exists. |

### Project fix runs (`/projects/{id}`) — find → ask permission → fix → re-check

Two explicit steps so consent is enforced on the backend (never by the UI alone):

| Method | Path | Description |
|--------|------|-------------|
| GET | `/projects/{id}/pipeline` | The 5-stage agent pipeline (`import`, `analyze`, `review`, `fix`, `verify`) derived from real records (`project_files`, `reviews`, `agent_executions`, `check_runs`/`check_steps`, `execution_runs`). Each stage: `state` (`WAITING`/`RUNNING`/`COMPLETED`/`FAILED`/`SKIPPED`), `startedAt`/`finishedAt`/`durationMs`, `detail`, and a display-safe `error`. A skipped sandbox reports `SKIPPED` + "Not verified: sandbox unavailable" — never `COMPLETED`. Owner only; empty projects return five `WAITING` stages (never 500). |
| POST | `/projects/{id}/fix-requests` | **Consent step.** Body `{ findingIds[], scopeNote? }` → 201 `{ projectId, fixRequestIds[], created, skipped }`. Creates one approved `FixRequest` per selected finding. Changes no code. |
| POST | `/projects/{id}/fix-runs` | **Fix + automatic re-check.** Body `{ findingIds[], scopeNote? }` → 200 fix-run detail. Rejected with **409** unless an approved `FixRequest` exists for the user and every selected finding; foreign/absent findings → 404. Per approved request it invokes the coding agent, applies the resulting patch, then runs the sandbox build/tests and verification. |
| GET | `/projects/{id}/fix-runs/{checkRunId}` | Fix-run detail: `status`, `steps[]`, before/after `summary` (`fixed`, `stillOpen`, `newlyIntroduced`), `changedFiles[]` (`path`, `patchId`, `status`, `diff`), `verificationVerdict`, and `sandboxSkipped`. Owner only (else 404). |

### Verification

| Method | Path | Description |
|--------|------|-------------|
| GET | `/patches/{patchId}/verification-runs` | Runs for patch (usually 1 + reruns). |
| GET | `/verification-runs/{runId}` | Detail: build status, test summary, deltas, verdict, log excerpts, duration. |
| GET | `/verification-runs/{runId}/tests?status=` | Per-test results (paged). |

### Generation (`/generations`)

Implemented contract (Generate workflow): the four-step wizard collects the
project and requirement, an explicit stack (including language/runtime and
“Other” free text), backend-generated clarifying questions, then a reviewed
plan. Nothing is generated until the user approves. The backend state is recorded in `AgentExecution`, execution evidence, and
verification rows. The frontend receives SSE change notifications and falls
back to visible-tab polling every three seconds. After verification and review,
a deterministic Rating Agent records a score and category breakdown.

| Method | Path | Description |
|--------|------|-------------|
| POST | `/generate/questions` | Step 3: `{ name, requirement (≥20 chars), description?, backend, frontend, languageRuntime, backendCustom?, frontendCustom? }` → backend-derived clarifying questions (`choice`/`text`/`yes-no`), each with `required`, `allowSkip` and `secret`. Creates no job and calls no LLM. |
| POST | `/generate/plan` | Step 4: `{ project, answers[] }` → stack-aware architecture, folder tree and file list built only from the explicit answers. Missing required answers → 400 naming each question id; no row is persisted. |
| POST | `/generations` | `{ name, requirement, description?, backend, frontend, languageRuntime?, backendCustom?, frontendCustom?, database, auth?, integrations?, databaseConfig?, aiConfig, answers?, databaseConfigureLater?, draft? }` → 201 + generation (`DRAFT` when `draft: true`). Secrets (`databaseConfig.password`, `aiConfig.apiKey`, and the database connection answer) travel only for the run, are held in memory only, and never appear in any response or persisted `answers`. |
| POST | `/generations/plan-preview` | Legacy stack-aware scaffold preview (`requirement`, stack, `auth`, `integrations`) → folders/files/architecture. Creates no job and makes no AI call. The wizard uses `/generate/plan`. |
| GET | `/generations` | List own generations (paged). Secrets masked (`passwordConfigured`, `keyConfigured` flags only). |
| GET | `/generations/{id}` | Generation state + masked config + `projectId` after handoff. Owner only (else 404). Carries `auth`, `integrations`, `execution` (with `notVerified`), `plan.files`, `agentWorkflow` timing/activity, the completed rating, finding summaries, and nullable `artifact` facts (never storage paths). |
| GET | `/generations/{id}/events` | Authenticated SSE stream of generation change notifications. The event contains no secrets or file contents; the client reloads the authoritative DTO. |
| GET | `/generations/{id}/files` | Files in the latest generated workspace: `{ path, sizeBytes }[]`. Owner only. |
| GET | `/generations/{id}/file?path=` | One generated text file for the browse view. Jailed to the workspace, 256 KB cap, binary files return `binary: true` with no content. |
| POST | `/generations/{id}/send-to-review` | Materializes a reviewed generation as a `GENERATED` `Project` (copy, never move), links `projectId`, and is idempotent. 409 until `REVIEWED`/`COMPLETED`. |
| POST | `/generations/{id}/revisions` | Append a task modification as a new numbered revision (drafts only, else 409). Body `{ requirement }`; response is the revision, never secrets. |
| GET | `/generations/{id}/revisions` | Ordered revision history for a generation. Owner only (else 404). |
| POST | `/generations/{id}/fix-requests` | Approval gate for the fix loop: creates one FixRequest per OPEN finding of the latest review (REVIEWED only, else 409). Body `{ scopeNote? }` → 202 `{ reviewId, created[], skippedOpen }`. Never mutates code. |
| POST | `/generations/{id}/rebuild` | Rebuild + reverify after an applied generation patch. Body `{ patchId }` (must be `APPLIED`) → 202. Backend owns the lifecycle state. |
| POST | `/generations/{id}/restructure/proposals` | Propose file moves for a preset (`MVC`, `LAYERED`, `FEATURE_FOLDERS`, `CUSTOM`) or custom description. Persists the proposal only; no file moves. |
| GET | `/generations/{id}/restructure/proposals` | Proposal history with moves, status, notes and `canRevert`. Owner only. |
| POST | `/generations/{id}/restructure/proposals/{proposalId}/apply` | Explicit approval gate. Backs up the workspace, then moves files; failure restores the backup. |
| POST | `/generations/{id}/restructure/revert` | Restores the applied proposal's backup exactly (moved copies removed), then marks it `REVERTED`. |
| GET | `/generations/{id}/download` | Generated project ZIP. Served when the artifact exists, generation review is complete, and the latest round has zero unresolved findings of any category; a sandbox build is not required. Verification level is reported separately as static checks, build-and-test checks, or sandbox unavailable. Otherwise 409 with `X-Download-Blocked-Reason`. ZIP carries an `.env.example` with placeholders — never real secrets. |

Rules: explicit enums only, no hidden defaults. Frontend `REACT_TYPESCRIPT`/`NEXTJS`/`VUE`/`OTHER`/`NONE`, backend `JAVA_SPRING_BOOT`/`PYTHON_FASTAPI`/`NODEJS`/`OTHER`/`NONE`, database `POSTGRESQL`/`MYSQL`/`MONGODB`/`SQLITE`/`NONE`, auth `JWT`/`OAUTH_GOOGLE`/`NONE`, integrations `STRIPE`/`RAZORPAY`/`EMAIL`/`FILE_STORAGE_S3`/`AI_LLM`/`MAPS`/`ANALYTICS`/`DOCKER_CI`, AI `OPENROUTER`/`CUSTOM`/`NONE`. `OTHER` requires `backendCustom`/`frontendCustom`. Reject backend+frontend both `NONE`, auth other than `NONE` without a backend, and Stripe+Razorpay together. `databaseConfig` is required iff database ≠ `NONE`; an explicit “configure later” answer resolves to database `NONE` with `databaseConfigureLater: true` and no fabricated config. `baseUrl` is required iff provider is `CUSTOM`. Provider `NONE` needs no key and takes no `apiKey`/`baseUrl` (model defaults to `template`): the backend builds a deterministic Java starter through the same gates (Java + React/none only). Non-secret answers persist in `answers`; a secret answer is stored only as `configured: true` with `value: null`. Generated code must use env-var placeholders — embedded secrets fail the run. If the Docker sandbox is disabled, build/test evidence is `SKIPPED`, Verify is `REJECTED`, the pipeline says **Not verified**, and the download gate stays locked. Output becomes a normal `GENERATED` project only through the explicit handoff endpoint.

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

- `multipart/form-data` with `file` (.zip), size ≤ 1 GB (configurable), MIME + magic-byte check, filename sanitized.
- ZIP entries capped (e.g. ≤50000 files, ≤4 GB uncompressed, no symlinks, no `..`, no absolute paths) — rejected with `VALIDATION_FAILED` + reason before any extraction.
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
