# VeriReview — Architecture

## 1. Decision: B — Modular Monolith (Spring Boot) + Separate Python AI Service

**Chosen: Option B.**

- `backend/` — Java Spring Boot **modular monolith**: all business logic, auth, persistence, REST API, job orchestration, sandbox dispatch.
- `ai-service/` — Python (LangGraph) **agentic service**: Planning, Review, Coding, Verified agents + LLM calls + tool schemas. No direct DB writes except via backend callbacks (or narrowly scoped, documented exception).
- `frontend/` — React + TypeScript + Vite + Tailwind dashboard (static SPA calling backend REST only).
- Shared infrastructure: PostgreSQL, Docker Compose, isolated `sandbox-runner` (Docker-based build/test executor).

### 1.1 Why not A (Microservices)?

- No independent scaling need per domain in v1; review/fix/verify share the same project/file model and transaction boundaries.
- Distributed transactions, inter-service auth, and eventual consistency would add cost without benefit.
- Resume signal favors demonstrating clean modular monolith (modules, transactions, JPA relationships) over premature distribution.
- Microservices remain a possible *future* extraction (e.g. dedicated `execution-service`) behind a stable interface — not now.

### 1.2 Why not C (Fully monolithic, incl. AI in Java)?

- Agent orchestration (graphs, retries, tool loops, prompt versioning, evaluation) is significantly more productive in Python with LangGraph/LangChain.
- LLM SDK churn is faster in Python; isolating it protects the Java backend's stability and dependency graph.
- Python gives direct access to analysis-helper scripting without polluting the enterprise backend.
- A clean HTTP boundary keeps each side testable and replaceable.

### 1.3 Why B wins

- Java backend demonstrates enterprise skills (layered architecture, JPA, security, transactions, pagination, auditing, async jobs, observability).
- Python service demonstrates modern AI engineering (LangGraph state machines, tool-constrained agents, evals).
- Single primary database (PostgreSQL, owned by Java backend) avoids split-brain business logic.
- Async needs in v1 are satisfied by Spring `@Async` / job tables, not Kafka.

## 2. System Context

```
                    ┌──────────────┐
                    │    React SPA │
                    │  (Vite + TS) │
                    └──────┬───────┘
                           │ HTTPS / REST + JWT
                    ┌──────▼───────┐        ┌──────────────────┐
                    │ Spring Boot  │◄──────►│   PostgreSQL     │
                    │ modular      │  JPA   │  (primary store) │
                    │ monolith     │        └──────────────────┘
                    └──────┬───────┘
                           │ REST (internal, authenticated)
                    ┌──────▼───────┐        ┌──────────────────┐
                    │ Python AI    │  LLM   │ OpenRouter       │
                    │ service      │───────►│ (LLM API)        │
                    │ (LangGraph)  │        └──────────────────┘
                    └──────┬───────┘
                           │ dispatch jobs (REST / queue later)
                    ┌──────▼───────┐
                    │ Sandbox      │  Docker-isolated: mvn build,
                    │ runner       │  tests, Checkstyle/PMD/SpotBugs,
                    │ (Docker)     │  OWASP scan. Time/mem/CPU caps.
                    └──────────────┘
```

- Frontend **never** calls the AI service or sandbox directly. All calls go through Spring Boot (policy enforcement, auth, audit).
- AI service **never** executes shell/build commands directly. It returns structured outputs; the backend dispatches sandbox jobs and feeds results back.
- Uploaded code **never** runs in the Spring Boot JVM.

## 3. Backend Module Structure (Modular Monolith)

Package root: `com.verireview` (to be confirmed in Phase 1).

```
backend/src/main/java/com/verireview/
  common/          # cross-cutting: exceptions, api envelope, pagination utils
  config/          # security, jackson, async, web, openapi, auditing
  security/        # JWT filter, userdetails, password encoding, RBAC annotations
  user/            # User, Role — controller/service/repository/dto/mapper
  project/         # Project, Repository, ProjectFile — ingestion orchestration
  ingestion/       # ZIP/paste/github importers, validation, storage abstraction
  review/          # Review, Finding — lifecycle + deterministic analysis dispatch
  analysis/        # deterministic tool adapters (checkstyle/pmd/spotbugs/owasp)
  fix/             # FixRequest, Patch — approval gate + diff handling
  verification/    # VerificationRun, TestResult — sandbox result intake + verdict intake
  agent/           # AI-service client, execution tracking (AgentExecution), orchestration
  execution/       # sandbox job dispatcher, timeouts, log capture
  audit/           # AuditLog writer, AOP or explicit service calls
  observability/   # metrics, health contributors
```

Module rules:

- Controllers are thin: validate DTO → call service → map to DTO. No business logic, no JPA entities leak.
- Services own transactions (`@Transactional` at service boundary, read-only by default for queries).
- Repositories are Spring Data JPA interfaces only; custom queries via derived names or `@Query`, paged where lists are user-facing.
- Mappers (MapStruct or manual — decided in Phase 1) convert entity ↔ DTO.
- Cross-module calls go via service interfaces, not repository sharing across aggregates.
- `common` never depends on domain modules.

Future extraction seam: `execution/` can become a standalone service later without changing agent or review contracts.

## 4. AI Service Structure

```
ai-service/
  app.py / main.py        # FastAPI entrypoint (internal API only)
  graphs/                 # LangGraph graphs: planning, review, coding, verified
  agents/                 # node implementations per agent
  tools/schemas.py        # Pydantic tool schemas (get_file, search_code, ...)
  prompts/                # versioned prompt templates per agent
  clients/                # backend callback client, LLM client (OpenRouter)
  evals/                  # agent workflow + tool tests
```

Contract principles:

- Backend → AI: `POST /internal/{planning,review,coding,verified}` with scoped context (project id, finding id, file snapshots or references — never raw secrets).
- AI → Backend: structured JSON (plan / findings list / patch diff / verdict recommendation + rationale). The **backend** applies policy (approval gates, sandbox dispatch, final verdict persistence).
- Verified Agent issues a *recommendation*; the backend records the verdict only when a matching sandbox run exists (enforced invariant).

## 5. Data Ownership

- PostgreSQL is owned by Spring Boot (Flyway/Liquibase migrations — decided Phase 2).
- AI service is stateless; it may cache prompts in memory but persists nothing authoritative. If it needs persistence (e.g. run traces), it POSTs back to the backend's `AgentExecution` API.
- File blobs: v1 stores on local filesystem volume (Docker-mounted) with DB metadata; S3-compatible abstraction reserved (interface first, local implementation).

## 6. Request Flows

### 6.1 Review flow (async)

1. `POST /api/v1/reviews` (backend validates ownership, creates `Review` QUEUED, writes audit).
2. Backend async worker: fetch files → run deterministic analysis adapters → call AI `review` graph with merged context → persist findings (source=DETERMINISTIC/AI) → mark COMPLETED.
3. Frontend polls `GET /api/v1/reviews/{id}` + `GET /api/v1/reviews/{id}/findings`.

### 6.2 Fix → Verify flow (async)

1. `POST /api/v1/findings/{id}/fix-requests` (explicit approval; one open fix per finding).
2. Backend calls AI `coding` graph → receives unified diff → validates diff (path traversal, file allowlist, size caps) → stores `Patch` PROPOSED.
3. Backend dispatches sandbox job (isolated copy + patch applied) → collects build/test/analysis results → stores `VerificationRun`.
4. Backend calls AI `verified` graph with evidence → receives recommendation → backend checks invariant (run exists, tests actually ran) → persists verdict VERIFIED/REJECTED.
5. Frontend shows diff + logs + verdict timeline.

## 7. Non-Goals in Architecture (v1)

- No Kafka: Spring async + `AgentExecution`/job-status polling suffices; Kafka arrives in Phase 13 only when multi-instance or retry/ordering demands it.
- No Redis: no proven hot path; add in Phase 13+ only for caching/rate-limit with a measured need.
- No MCP server / RAG: deferred to Phases 14–15; v1 passes scoped file snapshots explicitly.
- No direct agent shell access: all execution flows through the sandbox dispatcher.

## 8. Key Architectural Invariants

1. DTOs at every API boundary; entities never serialized.
2. Every state-changing agent step is tied to an `AgentExecution` row (who/what/when/input-hash/output-ref/duration).
3. No VERIFIED status without a linked `VerificationRun` with passing evidence.
4. Uploaded code path: validate → quarantine → analyze in sandbox only.
5. Fail safely: timeouts, caps, and cleanup on every external call (LLM, clone, build).
6. Audit log is append-only (no update/delete API).
