# VeriReview — Database Design (PostgreSQL)

> Status: design only. No migrations yet. Implemented in Phase 2 via Flyway *or* Liquibase (one only, decided Phase 2).

## 1. Modeling Principles

- One primary relational DB owned by Spring Boot. AI service persists nothing directly.
- Proper FKs everywhere; no orphan rows. Deletes are soft (`deleted_at`) for user-facing aggregates where history matters (Project, Review), hard-delete only for ephemeral blobs with retention policy.
- Enums stored as `VARCHAR` + JPA `@Enumerated(STRING)` (readable, migratable) — or Postgres enums only if team prefers; default VARCHAR for portability.
- Large/variable payloads (tool JSON, logs excerpts, diffs) in `TEXT`/`JSONB`; full build logs over a threshold go to object/file storage with a `log_ref`, not inline.
- Every table: `id UUID PK`, `created_at`, `updated_at` (Spring Data auditing). User-owned rows carry ownership FK for RBAC checks at query level.
- Pagination everywhere lists are user-facing (`Pageable`); indexes on all filter/sort columns.

## 2. Entities & Why Each Exists

| Entity | Purpose (why it exists) |
|--------|-------------------------|
| `users` | Identity, credentials ref (BCrypt hash — never plaintext), profile. Root of ownership. |
| `roles` | RBAC (`USER`, `ADMIN`); separate table (not enum-on-user) to allow future roles without migration of user rows + join audit. |
| `user_roles` | Join table. Users↔roles is many-to-many in principle even if v1 assigns one. |
| `projects` | Aggregate root for everything code-related. Owns ingestion source, language, status. Soft-deleted (history preserved). |
| `repositories` | GitHub import metadata (repo URL, branch, commit SHA, imported_at). Separate from `projects` because one project has ≤1 import record but distinct lifecycle; also allows future multi-repo projects. Nullable 1–1 to projects. |
| `project_files` | Normalized file inventory (path, language, size, hash, content ref). Enables file viewer, scoped agent snapshots, diff validation. Content itself: `TEXT` inline if small, else file-store ref (threshold decided Phase 5). |
| `reviews` | One review execution per project (status QUEUED/RUNNING/COMPLETED/FAILED, timestamps, duration). Groups findings + agent executions. |
| `findings` | Structured issue (category, severity, source, status, file/line, evidence). Heart of product; filterable/sortable. |
| `fix_requests` | Explicit user approval to fix ONE finding (status, scope note, requested_by). The approval gate — no patch without this row. |
| `patches` | Controlled diff for a fix request (unified diff, stats, status PROPOSED/APPLIED/REJECTED). Stored, never auto-applied to canonical tree. |
| `verification_runs` | One sandbox execution (build status, test totals, analysis deltas, log refs, duration). Evidence backing every verdict. |
| `test_results` | Per-test rows (or per-suite if volume demands) linked to a verification run. Enables "which test proved it" UI. |
| `agent_executions` | Trace of every agent call (agent type, model, prompt version, input hash, status, duration, error). Auditability + metrics + evals. |
| `audit_logs` | Append-only security trail (actor, action, entity, entity_id, metadata JSONB, IP). No update/delete API. |

Deliberately absent in v1: organizations/teams, comments, notifications, billing, refresh-token table (stateless JWT rotation via allowlist only if needed — decided Phase 3).

## 3. Relationships (Cardinality)

```
users 1───* projects (owner_id)
users *───* roles (via user_roles)

projects 1───0..1 repositories
projects 1───* project_files
projects 1───* reviews
projects 1───* agent_executions (nullable review/fix linkage for generation flow)

reviews 1───* findings
reviews 1───* agent_executions (review-agent runs)

findings 1───* fix_requests (usually 1 active; history preserved; partial unique index on (finding_id) WHERE status IN ('PENDING','IN_PROGRESS'))
fix_requests 1───* patches (usually 1; retries preserved)
patches 1───* verification_runs
verification_runs 1───* test_results
verification_runs 1───* agent_executions (verified-agent runs)

users 1───* audit_logs (actor; nullable for system actions)
```

Key constraints:

- `projects(owner_id, name)` unique per owner (prevents confusion; name editable with re-check).
- `project_files(project_id, path)` unique (one row per path; re-ingestion upserts by hash).
- `findings` carry `source` + `status` enums; status transitions validated in service layer (e.g. cannot go VERIFIED_FIXED without linked VERIFIED run).
- `verification_runs` must reference the `patch_id` it evaluated (NOT NULL) — enforces the "no verdict without evidence" invariant at the schema level.
- `audit_logs`: no FK cascade deletes; actor nullable SET NULL on user delete (trail survives).

## 4. Key Columns (Sketch)

- `users`: `email UNIQUE NOT NULL`, `password_hash NOT NULL`, `display_name`, `enabled BOOLEAN`, timestamps.
- `projects`: `owner_id FK`, `name`, `description`, `source_type (ZIP_UPLOAD|PASTE|GITHUB|GENERATED)`, `language`, `status`, `storage_ref`, `deleted_at NULL`.
- `repositories`: `project_id FK UNIQUE`, `repo_url`, `branch`, `commit_sha`, `imported_at`.
- `project_files`: `project_id FK`, `path`, `language`, `size_bytes`, `sha256`, `content TEXT NULL`, `content_ref NULL` (exactly one of content/content_ref set — check constraint).
- `reviews`: `project_id FK`, `status`, `started_at`, `finished_at`, `duration_ms`, `finding_count`, `error NULL`.
- `findings`: `review_id FK`, `category`, `severity`, `source`, `status`, `title`, `description`, `file_path NULL`, `line_start/line_end NULL`, `evidence JSONB`, `dedup_key` (tool+rule+path+line hash for deterministic merge).
- `fix_requests`: `finding_id FK`, `requested_by FK`, `status (PENDING|IN_PROGRESS|COMPLETED|FAILED|CANCELLED)`, `scope_note`, timestamps.
- `patches`: `fix_request_id FK`, `diff TEXT`, `files_changed INT`, `additions/deletions INT`, `status`, `validation_error NULL`.
- `verification_runs`: `patch_id FK NOT NULL`, `build_status`, `tests_total/passed/failed/skipped INT`, `static_delta JSONB`, `security_delta JSONB`, `verdict (PENDING|VERIFIED|REJECTED)`, `log_ref`, `duration_ms`.
- `test_results`: `verification_run_id FK`, `suite`, `test_name`, `status`, `duration_ms`, `message NULL`.
- `agent_executions`: `project_id FK`, `agent_type`, `model`, `prompt_version`, `status`, `input_hash`, `output_ref`, `duration_ms`, `error NULL`, links (`review_id/fix_request_id/verification_run_id` nullable).
- `audit_logs`: `actor_id FK NULL`, `action`, `entity_type`, `entity_id`, `metadata JSONB`, `ip NULL`, `created_at` (no updated_at — immutable).

## 5. Indexes

- `projects(owner_id, created_at DESC)`; `project_files(project_id, path)`; `reviews(project_id, created_at DESC)`.
- `findings(review_id, severity, status, category)` composite + `findings(dedup_key)`.
- `fix_requests(finding_id, status)`; `patches(fix_request_id)`; `verification_runs(patch_id)`; `test_results(verification_run_id, status)`.
- `agent_executions(project_id, agent_type, created_at DESC)`; `audit_logs(actor_id, created_at DESC)`, `audit_logs(entity_type, entity_id)`.

## 6. Data Volume & Retention

- Findings/test_results are the highest-volume tables → paged reads, no `SELECT *` without limits; purge policy for sandbox logs (e.g. keep excerpts inline 90 days, full logs in file store with TTL — finalized Phase 10).
- Uploaded ZIP originals retained per retention policy (decided Phase 5), then deleted with audit entry.

## 7. Migration Strategy (Phase 2)

- One migration tool (Flyway recommended default; Liquibase acceptable — ADR in Phase 2).
- `V1__init.sql` creates all tables above; later phases add columns via additive migrations only (no destructive alters without ADR + backup note).
- Seed migration inserts `USER`/`ADMIN` roles only — no demo users.
