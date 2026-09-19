# VeriReview — Development Roadmap

Incremental, always-buildable phases. Each phase lists Objective, Features, Files/Modules, Technologies, Dependencies, Tests, Verification criteria, and Definition of Done. Do not start the next phase until DoD is met.

---

## Phase 0 — Architecture and Repository Foundation

- **Objective:** freeze contracts and repo skeleton; record ADRs.
- **Features:** this docs set; repo layout (`backend/`, `frontend/`, `ai-service/`, `docs/`, `docker/`); `.gitignore`, `.env.example`, license; ADR-001 (Java 17 vs 21), ADR-002 (Flyway vs Liquibase), ADR-003 (mapper choice).
- **Files/modules:** `docs/*`, `AGENTS.md`, skeleton READMEs, `docker-compose.yml` stub.
- **Technologies:** Markdown, Docker Compose (stub), Git.
- **Dependencies:** owner approval of architecture + Java version.
- **Tests:** repo-lint (no secrets, required files exist).
- **Verification:** all 8 docs + AGENTS.md present and consistent; ADRs recorded.
- **DoD:** owner signs off on architecture/stack/phases; repo builds trivially (no app code yet).

## Phase 1 — Spring Boot Backend Foundation

- **Objective:** runnable modular monolith skeleton with health, errors, docs, logging.
- **Features:** Spring Boot 3 boot; `common` (envelope, `GlobalExceptionHandler`), `config` (Jackson, OpenAPI, auditing, async); Actuator health; structured logging with traceId; profiles (`dev`, `test`, `prod`).
- **Files/modules:** `backend/pom.xml`, `com.verireview.common.*`, `config/*`, `observability/*` (basic).
- **Technologies:** Java LTS (per ADR-001), Spring Boot/Web/Validation, Springdoc, Actuator, Maven.
- **Dependencies:** Phase 0 ADRs.
- **Tests:** context-loads smoke test; exception-envelope MVC test; health test.
- **Verification:** `mvn spring-boot:run` starts; `GET /actuator/health` 200; `/api-docs` renders; `mvn test` green.
- **DoD:** CI runs `mvn -B verify` green; Docker build of backend succeeds.

## Phase 2 — PostgreSQL Data Model

- **Objective:** versioned schema + JPA layer for all §DATABASE_DESIGN entities.
- **Features:** Compose `db` service; Flyway/Liquibase `V1__init.sql`; entities, repositories, auditing (`createdAt/updatedAt`), Flyway-safe enums-as-string; seed roles migration.
- **Files/modules:** `docker-compose.yml` (db), `*/entity/*`, `*/repository/*`, `db/migration/*`.
- **Technologies:** PostgreSQL 16, Spring Data JPA/Hibernate, migration tool (ADR-002), Testcontainers.
- **Dependencies:** Phase 1.
- **Tests:** repository slice tests (CRUD, unique constraints, soft-delete); migration-up-from-scratch test via Testcontainers.
- **Verification:** migrations apply cleanly on empty DB; `mvn verify` with Testcontainers green.
- **DoD:** ERD matches DATABASE_DESIGN; no entities exposed via API yet.

## Phase 3 — Authentication and Authorization

- **Objective:** secure identity + RBAC + audit trail.
- **Features:** register/login/refresh/logout/me; BCrypt; JWT (access+rotated refresh); RBAC USER/ADMIN; ownership predicates; append-only `audit_logs`; rate-limited auth endpoints (in-memory first).
- **Files/modules:** `security/*`, `user/*`, `audit/*`, `common` rate-limit filter.
- **Technologies:** Spring Security, JWT lib (one), Bucket4j or simple filter (documented).
- **Dependencies:** Phase 2.
- **Tests:** auth happy/negative paths; IDOR matrix tests; expired/tampered JWT; RBAC MVC tests; audit-row assertions.
- **Verification:** full auth flow via curl/Swagger; non-owner project access → 404; audit rows written.
- **DoD:** security review checklist (§SECURITY_DESIGN §2) passes; no endpoints leak entities.

## Phase 4 — React Frontend Foundation

- **Objective:** professional dashboard shell wired to backend auth.
- **Features:** Vite+TS strict+Tailwind; router (login/register/dashboard/projects/settings); API client (JWT + refresh + envelope); React Query polling hooks; login/register/dashboard pages; guarded routes; minimal design system (layout, cards, badges for severity/status).
- **Files/modules:** `frontend/src/{api,routes,pages,components,hooks,styles}`.
- **Technologies:** React, TS, Vite, Tailwind, React Router, TanStack Query, Vitest+RTL.
- **Dependencies:** Phase 3 API (auth + users).
- **Tests:** component tests (auth forms, guards); API-client tests (refresh flow, envelope errors); typecheck + lint clean.
- **Verification:** `npm run dev` + backend → register/login/logout works in UI; tokens refresh silently; `npm test` green.
- **DoD:** deployable static build (`npm run build`) served behind Compose; no direct AI/sandbox calls from frontend.

## Phase 5 — Project Ingestion

- **Objective:** safe multi-source project intake + file browser.
- **Features:** project CRUD (paged/filter/sort); ZIP upload pipeline (§SECURITY_DESIGN §3); paste-file intake; GitHub shallow-clone import (public URL allowlist, async 202); file tree + capped content viewer + search; retention + quarantine cleanup.
- **Files/modules:** `project/*`, `ingestion/*` (zip/paste/github importers, storage abstraction), frontend projects + project-detail + file-viewer pages.
- **Technologies:** Spring Web multipart, local volume storage (S3 interface reserved), JGit or CLI git (arg-array only).
- **Dependencies:** Phases 3–4.
- **Tests:** upload attack fixtures (ZipSlip/symlink/bomb/traversal) rejected; GitHub URL allowlist tests; file-jail tests; UI file-browser tests.
- **Verification:** upload sample Java ZIP → tree renders; paste → file stored; GitHub import → commit SHA recorded; attack fixtures all 4xx + audit entries.
- **DoD:** no ingestion path executes code; all caps enforced + documented in OpenAPI.

## Phase 6 — Deterministic Code Analysis

- **Objective:** reproducible tool findings without any LLM.
- **Features:** analysis adapters (Checkstyle, PMD, SpotBugs, OWASP Dependency-Check) behind common interface; tool-version-pinned sandbox image; normalized `DeterministicFinding` JSONB; before/after diff support (for Phase 10 reuse); per-review analysis section in UI.
- **Files/modules:** `analysis/*`, `execution/*` (v1 local-dispatch; Docker-hardened later), sandbox `Dockerfile.analysis`.
- **Technologies:** Checkstyle/PMD/SpotBugs/OWASP tools, Docker.
- **Dependencies:** Phase 5 (project files available).
- **Tests:** golden Java fixtures asserting each tool fires expected rule; adapter unit tests (parse/malformed-output); version-pin test.
- **Verification:** run on sample repo → deterministic findings persisted with rule IDs + file/line refs; rerun is reproducible (same findings, same versions).
- **DoD:** Review Agent contract can consume this output (schema frozen).

## Phase 7 — Review Agent

- **Objective:** AI findings layered over deterministic results (read-only).
- **Features:** FastAPI `ai-service` + LangGraph review graph (§AGENT_DESIGN); backend `/reviews` async orchestration (QUEUED→RUNNING→COMPLETED/FAILED); finding merge/dedupe/rank; `AgentExecution` tracing; prompt versioning; internal contract auth.
- **Files/modules:** `ai-service/graphs+agents+prompts/review/*`, backend `review/*`, `agent/*`, frontend review-trigger + status polling.
- **Technologies:** Python 3.12, FastAPI, LangGraph, Pydantic, OpenRouter API.
- **Dependencies:** Phase 6 (deterministic input), Phase 3 (service auth).
- **Tests:** eval golden set (recall/category/severity); schema-validation tests; merge/dedupe unit tests; backend orchestration tests (mock AI client).
- **Verification:** sample review completes end-to-end; findings show correct `source` labels; no VERIFIED labels appear; trace row exists per run.
- **DoD:** eval suite passes in CI; token usage logged; timeouts/retries behave.

## Phase 8 — Findings and Review Dashboard

- **Objective:** first-class finding UX + history.
- **Features:** findings list (filter severity/category/status, sort, paginate); finding detail (evidence, file/line link, source badge); review history; WONTFIX-with-reason; agent-timeline view; severity/status badges.
- **Files/modules:** frontend findings + review-history + timeline components; backend query endpoints (already in API_DESIGN).
- **Technologies:** React Query polling, backend `Pageable` + specifications.
- **Dependencies:** Phase 7.
- **Tests:** backend filter/sort/pagination tests; frontend list/detail/timeline tests; WONTFIX audit test.
- **Verification:** 100-finding fixture paginates/filters correctly; timeline shows deterministic→AI stages with durations.
- **DoD:** demo-ready review loop (upload → review → findings) with zero fix functionality yet.

## Phase 9 — Coding Agent and Controlled Patches

- **Objective:** minimal, validated diffs behind an explicit approval gate.
- **Features:** `POST /findings/{id}/fix-requests` gate (409 if open); LangGraph coding graph; unified-diff output; backend diff validator (jail, caps, binary/denylist); `Patch` storage (PROPOSED); diff viewer UI; cancel endpoint.
- **Files/modules:** `ai-service/.../coding/*`, backend `fix/*`, frontend fix-request + diff-viewer.
- **Technologies:** LangGraph coding graph; Java diff-parse/validation; diff UI component.
- **Dependencies:** Phases 7–8.
- **Tests:** malicious-diff fixtures rejected; oversized/out-of-scope diffs rejected; "claims verified" language rejected; scope-budget tests; UI diff-render tests.
- **Verification:** fix request on real finding → PROPOSED patch within budget, viewable diff, no canonical files modified.
- **DoD:** invariant holds: no patch touches canonical tree; every patch links to exactly one FixRequest.

## Phase 10 — Build/Test Verification (Sandbox v1)

- **Objective:** isolated proof that a patch builds and tests pass.
- **Features:** `sandbox-runner` container (snapshot → apply patch → `mvn verify` + analysis re-run → structured result); backend `VerificationRun` + `TestResult` persistence; run-detail + per-test UI; log excerpts + `log_ref`; timeouts + cleanup always.
- **Files/modules:** backend `verification/*`, `execution/*` (dispatcher), `docker/sandbox/*`, frontend run + test-results views.
- **Technologies:** Docker (runner), Maven/Gradle-in-sandbox, JUnit XML parsing.
- **Dependencies:** Phase 9.
- **Tests:** golden pass/fail fixtures (failing test → run FAILED, not VERIFIED); timeout-kill test; workspace-cleanup test; zero-tests-run → not-verified test.
- **Verification:** propose patch on sample bug → sandbox run completes with build+test evidence visible in UI.
- **DoD:** nothing user-supplied ever executes outside sandbox (reviewed + tested).

## Phase 11 — Verified Agent

- **Objective:** evidence-based VERIFIED/REJECTED verdicts; close the "Generate. Review. Fix. Verify." loop.
- **Features:** LangGraph verified graph; 4-gate check (build green, target tests passed, no new CRITICAL/HIGH, original check clean); backend cross-check (refuse VERIFIED if run contradicts); verdict + rationale + residual risks UI; generation flow (plan → approve → scaffold → review → fix → verify) wired end-to-end.
- **Files/modules:** `ai-service/.../verified+planning/*`, backend `verification` verdict logic + `generate/*`, frontend verdict + generation pages.
- **Technologies:** same agent stack; planning prompts + plan-approval endpoints.
- **Dependencies:** Phases 9–10.
- **Tests:** eval matrix (pass→VERIFIED, fail→REJECTED, zero-tests→REJECTED, new-CRITICAL→REJECTED, contradictory-recommendation overridden by backend); generation golden test (TODO API scaffold builds).
- **Verification:** full loop on sample bug shows VERIFIED with cited evidence; broken fix shows REJECTED with reason.
- **DoD:** "no VERIFIED without linked passing run" enforced in code + covered by test; portfolio demo complete.

## Phase 12 — GitHub Integration (Full)

- **Objective:** production-grade repo connection beyond public-clone.
- **Features:** OAuth app login (minimal scopes) *or* documented PAT path (one only — ADR); repo metadata; PR creation with verification summary; webhook intake (signature-verified) for push/PR events; optional PR comments with findings.
- **Files/modules:** backend `github/*` (client, webhook controller, PR service), frontend repo-connect + PR views.
- **Technologies:** GitHub REST API, webhooks (HMAC verification), encrypted token storage.
- **Dependencies:** Phase 11 (verification summary to post).
- **Tests:** webhook-signature tests; scope-minimality review; PR-body golden tests; token-encryption tests.
- **Verification:** import private fixture repo → open PR with verification comment in sandbox org.
- **DoD:** SSRF/allowlist controls extended to API calls; no excess scopes.

## Phase 13 — Kafka Asynchronous Processing

- **Objective:** durable async only when scale demands it (trigger: multi-instance or retry/ordering pain).
- **Features:** events `REVIEW_REQUESTED/COMPLETED`, `FIX_REQUESTED/COMPLETED`, `VERIFICATION_REQUESTED/COMPLETED`; outbox or direct publish from services; idempotent consumers; DLQ + retries with backoff; UI unchanged (polls same endpoints).
- **Files/modules:** new `messaging/*` module (producer/consumer/topology), Compose `kafka` profile.
- **Technologies:** Kafka (KRaft, single broker locally), Spring for Kafka.
- **Dependencies:** Phase 11 stable; skip entirely if single-instance suffices (document decision).
- **Tests:** consumer idempotency tests; poison-message → DLQ tests; ordering tests per aggregate key.
- **Verification:** kill consumer mid-job → redelivery succeeds exactly-once-effect; review still completes.
- **DoD:** sync path remains as fallback documented; no event without consumer + alerting.

## Phase 14 — MCP Tools

- **Objective:** formalize agent tools with explicit permissions.
- **Features:** MCP server exposing `get_project_structure/get_file/search_code/get_git_diff/run_build/run_tests/run_static_analysis/get_test_results/create_patch` (+ scoped GitHub ops); per-agent permission manifests; all execution still backend-dispatched (MCP calls the dispatcher, never shell).
- **Files/modules:** `mcp-server/*` (or `ai-service/mcp/*`), agent tool-call migration, permission manifests.
- **Technologies:** MCP SDK (Python), JSON-RPC tool schemas.
- **Dependencies:** Phases 7–11 (tool semantics already proven via direct calls).
- **Tests:** permission tests (coding agent cannot call admin tools; review agent cannot create patches); schema tests per tool; injection-resistance tests.
- **Verification:** disable one tool permission → agent degrades gracefully with audit entry.
- **DoD:** zero unrestricted-shell paths; every tool call logged with agent + permission check.

## Phase 15 — RAG / Project Context

- **Objective:** project-aware retrieval only where chunking fails.
- **Features:** pgvector embeddings for README/docs/standards/prior findings; retrieval step in review graph; cited-sources in findings; re-index on ingestion; eval comparing with/without RAG.
- **Files/modules:** `rag/*` (chunk/index/retrieve), migration enabling pgvector, ai-service retriever.
- **Technologies:** PostgreSQL + pgvector, embedding model via OpenRouter (pinned).
- **Dependencies:** Phase 7 evals (baseline to beat).
- **Tests:** retrieval precision/recall tests on fixture corpus; citation-presence tests; PII/secret exclusion tests (no secrets indexed).
- **Verification:** large-repo review quality improves on evals or RAG stays off by default (document outcome).
- **DoD:** RAG is additive and toggleable; no finding without cited chunk passes as "context-backed".

## Phase 16 — Docker Sandbox Hardening

- **Objective:** production-grade isolation + quotas.
- **Features:** `--network=none` (allowlisted proxy for Maven Central), read-only rootfs + tmpfs, seccomp/AppArmor profiles, cgroup caps, image allowlists, per-user concurrency quotas, forensic log capture, periodic image rebuilds.
- **Files/modules:** `docker/sandbox/*` (profiles, seccomp JSON), `execution/*` quota logic.
- **Technologies:** Docker runtime flags, Linux seccomp/cgroups.
- **Dependencies:** Phase 10 (semantics unchanged, only hardening).
- **Tests:** escape-attempt fixtures (network exfil, fork bomb, fs escape) contained; quota-exceeded → 429; post-run workspace absence verified.
- **Verification:** third-party-style checklist walkthrough recorded as ADR/sign-off.
- **DoD:** hardening defaults on in Compose prod profile; local dev profile documented as weaker.

## Phase 17 — Observability

- **Objective:** metrics, dashboards, alerts on what matters.
- **Features:** Micrometer timers (review/agent/build/verify durations), counters (verification success rate, job failures), API latency histograms; Prometheus + Grafana Compose profile + provisioned dashboards; structured JSON logs; alert rules (job failure spikes, sandbox timeout spikes).
- **Files/modules:** `observability/*`, `docker/observability/*`.
- **Technologies:** Actuator, Micrometer, Prometheus, Grafana.
- **Dependencies:** Phases 7–11 (metrics hooks in job paths).
- **Tests:** metric-presence tests (run a review → duration metric emitted); dashboard JSON validated in CI.
- **Verification:** Grafana shows review/build/verify durations + verification success rate from live run.
- **DoD:** SLOs documented (NFR-03/04); alerts fire to a documented channel (even if just log in v1).

## Phase 18 — CI/CD and Deployment

- **Objective:** green-pipeline discipline + minimal-cost deploy.
- **Features:** GitHub Actions (backend `verify` + Testcontainers, frontend typecheck/test/build, ai-service pytest, Docker builds, secret + dependency + container scans); branch protection guidance; minimal AWS design (one container host + managed Postgres, cost-capped; Compose-compatible); env/secret management runbook.
- **Files/modules:** `.github/workflows/*`, `Dockerfile.*`, deploy docs.
- **Technologies:** GitHub Actions, Docker, AWS (minimal: e.g. ECS Fargate or single EC2 + RDS — decided by ADR with cost note).
- **Dependencies:** all prior phases (pipeline covers them).
- **Tests:** pipeline itself (failing test blocks merge — demonstrated); scan gates on CRITICAL.
- **Verification:** fresh clone → `docker compose up` → full demo loop works; PR with intentional failure goes red.
- **DoD:** `main` always deployable; image SBOMs stored.

## Phase 19 — Production Hardening

- **Objective:** safe, boring production readiness.
- **Features:** rate-limit tightening + abuse quotas; backup/restore drills (DB + file store); data-retention enforcement; GDPR delete path; pagination/cap audit; dependency refresh; load smoke (k6 or equivalent on read paths); final security checklist sign-off (§SECURITY_DESIGN); resume-story polish (screenshots, demo script, architecture diagram export).
- **Files/modules:** runbooks in `docs/ops/*`, final config tightening.
- **Technologies:** backups (pg_dump + volume snapshots), k6 (or equivalent).
- **Dependencies:** Phase 18.
- **Tests:** restore drill test; quota/rate-limit tests under load profile; final full E2E (upload → verify) on prod-like Compose.
- **Verification:** checklists signed; demo script reproducible twice in a row by a fresh operator.
- **DoD:** v1 declared done; deferred items (if any) moved to explicit v2 backlog with trigger conditions.
