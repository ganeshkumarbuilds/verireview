# VeriReview — Product Requirements

> Tagline: "Generate. Review. Fix. Verify."

## 1. Vision

VeriReview is an AI-powered software engineering platform for developers who do not trust unverified AI output.

Core philosophy: **AI must not simply claim code is correct. VeriReview independently verifies AI-generated or AI-modified code using deterministic tools, compilation, tests, and static/security analysis.**

The product demonstrates two things simultaneously:

1. A useful developer tool (upload → review → fix → verify loop).
2. Strong Java Full Stack engineering (enterprise Spring Boot backend + professional React dashboard + isolated Python agentic service).

## 2. Target Users

Primary: individual developers / senior students / hiring reviewers evaluating:

- code quality awareness,
- security awareness,
- backend architecture skill,
- AI-agent system design skill.

Secondary (future): small teams wanting a second-opinion reviewer before merging PRs.

Non-goals: full IDE replacement, full CI platform (GitHub Actions covers that), auto-merging bots.

## 3. Product Scope

### 3.1 In scope (v1)

- User accounts with RBAC (USER, ADMIN).
- Project ingestion via three methods:
  1. ZIP upload,
  2. Paste single file / snippet,
  3. GitHub repository import (via public clone URL first; OAuth later).
- AI project generation from natural-language requirement (Planning → Coding → Review → Fix → Verify).
- Deterministic static analysis for Java (Checkstyle, PMD, SpotBugs, OWASP Dependency-Check).
- Review Agent producing structured findings.
- Finding detail view + fix request workflow.
- Coding Agent producing minimal controlled patches (unified diff).
- Isolated build + test execution (Docker sandbox).
- Verified Agent producing Verified / Rejected verdict with evidence.
- Developer dashboard showing full agent timeline and evidence.
- Review history, audit log.

### 3.2 Out of scope (v1, deferred)

- Auto-commit / auto-merge to GitHub.
- Multi-language deep analysis (Python/JS/TS superficial support only, if any).
- IDE plugins.
- Team workspaces / organizations.
- Billing.
- RAG over full codebase (deferred to Phase 15).
- MCP tool server (deferred to Phase 14).
- Kafka event bus (deferred to Phase 13; synchronous REST + async job executor first).

## 4. Core Workflows

### 4.1 Existing-project workflow

1. User uploads ZIP / pastes code / imports GitHub repo.
2. System ingests → stores normalized project files + metadata.
3. User triggers Review.
4. System runs deterministic analysis (linters, SAST, dependency scan).
5. Review Agent consumes code context + deterministic results → structured findings.
6. Dashboard lists findings by severity/category.
7. Developer selects a finding → creates FixRequest (explicit approval).
8. Coding Agent reads scoped files → returns controlled patch (diff, not full rewrite).
9. System applies patch to an isolated copy → runs build → tests → static analysis → security checks.
10. Verified Agent evaluates evidence → verdict: VERIFIED / REJECTED (+ rationale + test/build references).
11. Dashboard shows before/after diff, build logs, test results, verdict.

Invariant: **no fix is ever marked "verified" on the Coding Agent's word alone.**

### 4.2 Generated-project workflow

1. User enters natural-language requirement.
2. Planning Agent produces structured plan (features, architecture, tech, entities, APIs, tests).
3. User approves/edits plan (explicit approval gate).
4. Coding Agent scaffolds generated project from plan.
5. Review Agent reviews generated code.
6. Fix loop (same as §4.1) until verified or user stops.
7. User downloads final project ZIP.

Approval gates: plan approval and per-finding fix approval. No destructive or large-scale change without explicit user action.

## 5. Functional Requirements

| ID | Area | Requirement |
|----|------|-------------|
| FR-01 | Auth | Register, login (JWT access + refresh rotation), logout, current-user profile |
| FR-02 | AuthZ | RBAC: USER owns only own projects; ADMIN can view system metrics/audit |
| FR-03 | Projects | CRUD projects; list with pagination/filter/sort; ownership enforced |
| FR-04 | Ingestion-ZIP | Accept `.zip` ≤ defined limit (e.g. 50 MB), validate MIME/extension, safe extraction (ZipSlip protection), file-count/size caps |
| FR-05 | Ingestion-Paste | Accept single file: filename + language + content, size-capped, validated |
| FR-06 | Ingestion-GitHub | Accept public repo URL (allowlist github.com), shallow clone by branch/commit, size cap |
| FR-07 | Files | Browse project tree, view file content (read-only, size-capped), search |
| FR-08 | Review | Trigger review per project; track status (QUEUED/RUNNING/COMPLETED/FAILED) |
| FR-09 | Findings | List/filter by severity, category, status; finding detail with evidence, file/line refs |
| FR-10 | Fix | Create FixRequest for one finding at a time; approve scope explicitly |
| FR-11 | Patch | Store patch as unified diff + file-level stats; never auto-apply to canonical project without verification |
| FR-12 | Verification | Run build+tests+analysis in sandbox; store logs, test counts, verdict |
| FR-13 | Generation | NL requirement → plan → user approval → scaffolded project |
| FR-14 | History | Review history, fix history, verification history per project |
| FR-15 | Audit | Append-only audit log of security-relevant actions |
| FR-16 | Settings | User settings (e.g. LLM model preference within allowlist, notification prefs) — minimal v1 |

## 6. Finding Taxonomy

Categories: `BUG`, `SECURITY`, `CODE_QUALITY`, `PERFORMANCE`, `ARCHITECTURE`, `DEPENDENCY`, `MISSING_TEST`, `STYLE`.

Severity: `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `INFO`.

Each finding must carry:

- `source`: `DETERMINISTIC` | `AI` | `VERIFIED` (promotion only after verification stage confirms or reproduces it).
- `status`: `OPEN` | `FIX_REQUESTED` | `FIX_PROPOSED` | `VERIFIED_FIXED` | `REJECTED` | `WONTFIX` (with reason).
- Evidence: rule ID / tool name (deterministic) or quoted code + reasoning (AI).
- File path + line range (if applicable).

A finding is never silently deleted to "pass" a check.

## 7. Non-Functional Requirements

| ID | Requirement | Target (v1) |
|----|-------------|-------------|
| NFR-01 | Verification-first | 100% of VERIFIED verdicts must reference a build/test/analysis run ID |
| NFR-02 | Isolation | Uploaded code never executes in Spring Boot JVM; only in Docker sandbox with timeout + limits |
| NFR-03 | API latency | p95 < 300 ms for CRUD reads (excluding agent/build jobs) |
| NFR-04 | Async jobs | Review/fix/verify run async; UI polls or uses SSE; no request blocks > 30 s |
| NFR-05 | Upload safety | ZIP bombs, path traversal, symlink escapes rejected; scanned before storage |
| NFR-06 | Secrets | No secrets in code/logs; env/secret-manager only |
| NFR-07 | Auditability | Every agent execution, patch, verification run traceable to user + project + timestamp |
| NFR-08 | Maintainability | Modular monolith; DTOs at boundaries; ≥70% backend line coverage on domain/service layers (target, enforced in CI) |
| NFR-09 | Observability | Structured logs + Actuator health + duration metrics for review/build/verify |
| NFR-10 | Portability | Full local run via Docker Compose |

## 8. Success Criteria (Portfolio / v1 Done)

- End-to-end existing-project loop works on a sample Java repo: upload → review → 1 fix → build/test → VERIFIED or REJECTED with evidence visible in UI.
- End-to-end generation loop works for one small template project (e.g. TODO REST API).
- No unverified fix is displayed as verified anywhere in UI/API.
- `docker compose up` starts frontend + backend + db + ai-service + sandbox runner locally.
- CI runs backend tests, frontend checks, and container builds on every PR.

## 9. Open Questions (Require Owner Approval)

See §8 of the final report; key ones: Java LTS version pin (17 vs 21), JWT-only vs OAuth2-login scope, GitHub import depth for v1, and max sandbox resource limits.
