# VeriReview — Tech Stack

## 1. Guiding Rule

Every technology must have a real architectural purpose. Nothing is added for resume appearance. Deferred items (Redis, Kafka, MCP, RAG, cloud) are documented with the *trigger condition* that would justify them.

## 2. Backend (Primary)

| Technology | Version / Choice | Purpose / Justification |
|------------|------------------|-------------------------|
| Java LTS | **17 or 21 (TBD Phase 0, default 21)** | Modern LTS; records, pattern matching, virtual threads (21). Pin one version via toolchain + CI matrix. Decision requires owner approval. |
| Spring Boot | 3.x (latest stable at Phase 1) | Primary API + business backend; auto-config, Actuator, validation, testing slice support |
| Spring Web (MVC) | — | REST controllers, exception handling (`@ControllerAdvice`), pagination (`Pageable`) |
| Spring Security + JWT | Resource-server or custom filter (decided Phase 3) | AuthN/AuthZ, RBAC, password hashing (BCrypt), access/refresh rotation |
| Spring Data JPA + Hibernate | — | Repositories, relationships, auditing (`@CreatedDate`, `@EntityListeners`) |
| Bean Validation (`jakarta.validation`) | — | DTO validation (`@NotBlank`, file-size constraints, URL allowlists) |
| PostgreSQL | 16.x | Primary relational store; JSONB for tool payloads; pgvector extension reserved for Phase 15 |
| Flyway *or* Liquibase (TBD Phase 2) | one only | Versioned schema migrations; repeatable + undo discipline |
| MapStruct (or manual mappers) | TBD Phase 1 | Entity↔DTO mapping without leaking JPA into API |
| Springdoc OpenAPI | — | `/api-docs` + Swagger UI for API contract |
| Logback + structured JSON option | — | Structured logging (trace IDs for agent runs) |
| Micrometer + Actuator | — | Health, metrics (review/build/verify durations) feeding Prometheus later |
| JUnit 5 + Mockito + Spring Boot Test + Testcontainers | — | Unit, slice, and integration tests (real Postgres in CI) |
| Maven | — | Build, dependency management, Checkstyle/PMD/SpotBugs self-application (dogfooding) |
| Docker | — | Backend image; sandbox base images; Compose for local dev |

Explicitly **not** in v1 backend: Kafka client, Redis client, MongoDB, GraphQL, gRPC (REST suffices for all v1 contracts).

## 3. Frontend

| Technology | Purpose |
|------------|---------|
| React 18+ + TypeScript (strict) | Dashboard SPA; type-safe API clients (generated from OpenAPI where feasible) |
| Vite | Dev server + production build |
| Tailwind CSS | Utility styling; professional dark developer-console aesthetic |
| React Router | Routes: login, dashboard, projects, project detail, review/findings, settings |
| TanStack Query (React Query) | Server-state, polling for async jobs (reviews, verification runs) |
| Axios / fetch wrapper | Single API client with JWT attachment + refresh + error envelope handling |
| Vitest + React Testing Library (+ Playwright for E2E later) | Component/integration tests; E2E only for critical loops |

No UI framework lock-in beyond Tailwind; no state-management library (Redux/Zustand) until a proven need — React Query + local state suffices for v1.

## 4. AI Service

| Technology | Purpose |
|------------|---------|
| Python 3.12+ | Agent runtime |
| FastAPI | Internal-only HTTP API (`/internal/*`); Pydantic validation; health endpoint |
| LangGraph | Agent state machines (planning/review/coding/verified graphs with explicit nodes/edges) |
| LangChain (selective) | LLM wrappers / output parsers where they reduce code; not a blanket dependency |
| Pydantic v2 | Tool schemas + structured outputs (findings, patches, verdicts) |
| OpenRouter-compatible API | Model-agnostic LLM access (model pinned per agent via config, overridable per env) |
| pytest + httpx test client | Graph/node/tool/eval tests |

Boundary: AI service holds **zero** business authority. It proposes; Spring Boot disposes (approval gates, sandbox dispatch, verdict persistence). No shared ORM models; contracts are versioned JSON schemas (see `API_DESIGN.md` §7).

## 5. Deterministic Analysis (Java focus)

| Tool | Catches |
|------|---------|
| Checkstyle | Style/convention violations |
| PMD | Code-quality / bad practices |
| SpotBugs (+ FindSecBugs where stable) | Bytecode bug patterns, some security smells |
| OWASP Dependency-Check (or Dependabot-equivalent scan step) | Known-vulnerable dependencies (CPE/CVE) |

Each adapter normalizes to a common `DeterministicFinding` shape before the Review Agent sees it. Tool versions pinned in sandbox image for reproducibility. Other languages: out of v1 scope beyond plain file viewing.

## 6. Infrastructure / DevOps

| Technology | Purpose |
|------------|---------|
| Docker + Docker Compose | Local orchestration: `frontend`, `backend`, `db`, `ai-service`, `sandbox-runner` profiles |
| GitHub Actions | CI: backend build+tests, frontend typecheck+tests, image builds, secret scanning |
| Prometheus + Grafana (Phase 17) | Metrics backend; not bundled in v1 Compose by default (profile-gated) |
| AWS (Phase 18+, minimal) | Container hosting (e.g. single ECS/Fargate or EC2 + RDS) — cost-capped design only, no over-architecting now |

## 7. Deferred Technologies + Trigger Conditions

| Technology | Trigger to introduce |
|------------|----------------------|
| Redis | Measured hot path (e.g. finding-list caching, GitHub metadata) or need for distributed rate limiting across instances |
| Kafka | Multiple backend replicas needing durable review/fix/verify events, retries, ordering (`REVIEW_REQUESTED`, etc.) |
| MCP | ≥2 agent tools needing standardized permissioned invocation; then build one tool server |
| pgvector / RAG | Repos large enough thatStuff-everything-in-context fails; retrieval precision demonstrably improves finding quality |
| OAuth2 login (GitHub) | User demand for social login; otherwise JWT is enough for v1 |
| S3-compatible storage | File blobs exceed local-volume practicality or multi-instance backends need shared storage |

## 8. Version Pinning Policy

- All versions pinned in `backend/pom.xml`, `frontend/package.json` (+ lockfile), `ai-service/requirements.txt` (or `pyproject.toml`), and sandbox `Dockerfile`.
- Dependabot/Renovate enabled from Phase 18.
- Java version decision (17 vs 21) recorded as ADR-001 in Phase 0.
