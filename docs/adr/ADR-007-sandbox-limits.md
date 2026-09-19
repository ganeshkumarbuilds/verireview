# ADR-007 — Docker Sandbox Initial Limits

Status: Accepted (Phase 0, owner-approved). Configurable at runtime via `.env`.

## Context

`docs/SECURITY_DESIGN.md` requires isolated Docker execution with timeout + CPU/mem caps + cleanup. Owner froze v1 limits in Phase 0.

## Decision

Initial sandbox limits (runtime-overridable via env, see `.env.example`):

| Limit | Value | Env var |
|-------|-------|---------|
| Execution timeout | 60 s | `SANDBOX_TIMEOUT_SECONDS` |
| Memory | 512 MB | `SANDBOX_MEMORY_MB` |
| CPU | 1 core | `SANDBOX_CPU_COUNT` |
| Captured output | 5 MB | `SANDBOX_MAX_OUTPUT_MB` |
| Project size | 50 MB | `SANDBOX_MAX_PROJECT_MB` |
| File count | 2000 files | `SANDBOX_MAX_FILES` |

Uploaded/generated code never executes in the Spring Boot JVM, frontend, or AI-service shell — only the sandbox dispatcher (Phase 10), hardened in Phase 16.

## Consequences

- Runs exceeding any limit are killed and marked failed with a reason (never silently truncated to a pass).
- Limits surface in API errors/logs where actionable (without leaking host details).

## Verification

- Phase 10: timeout-kill, output-cap, and workspace-cleanup tests; Phase 16: quota + escape-containment fixtures.
