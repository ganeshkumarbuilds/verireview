# VeriReview — "Generate. Review. Fix. Verify."

AI-powered software engineering platform. AI proposes; deterministic tools + sandbox + backend policy verify.

## Status

**Phase 0 — Architecture and repository foundation.** No application features implemented yet.

## Docs (read first)

- `docs/PRODUCT_REQUIREMENTS.md`
- `docs/ARCHITECTURE.md`
- `docs/TECH_STACK.md`
- `docs/AGENT_DESIGN.md`
- `docs/DATABASE_DESIGN.md`
- `docs/API_DESIGN.md`
- `docs/SECURITY_DESIGN.md`
- `docs/DEVELOPMENT_ROADMAP.md`
- `docs/adr/` — finalized Architecture Decision Records
- `AGENTS.md` — permanent development rules (binding)

## Finalized decisions (Phase 0)

- Java 21 LTS, Spring Boot 4.1.1, Maven
- PostgreSQL 16, Flyway, MapStruct
- React + TypeScript + Vite + Tailwind
- Python 3.12 + FastAPI + LangGraph
- Spring Boot modular monolith + separate Python AI service (no direct PG writes from Python)
- Public GitHub repository import for v1 (OAuth deferred)
- Sandbox initial limits: timeout 60s, memory 512 MB, CPU 1 core, output 5 MB, project 50 MB, 2000 files
- Backend-enforced verification: VERIFIED only when build passed + tests passed + no new CRITICAL + no new HIGH
- Deferred to later phases: GitHub OAuth, Kafka, Redis, MCP, RAG, advanced sandbox hardening, observability, AWS

## Repository layout

```
backend/      # Phase 1+: Spring Boot modular monolith (empty in Phase 0)
frontend/     # Phase 4+: React SPA (empty in Phase 0)
ai-service/   # Phase 7+: Python FastAPI + LangGraph (empty in Phase 0)
docker/       # Phase 2+: Dockerfiles, sandbox profiles (notes only in Phase 0)
docs/         # Planning docs + ADRs
scripts/      # Dev/validation scripts (Phase 0 lint only)
docker-compose.yml  # Phase 0 stub: PostgreSQL 16 only
```

## Quick start (Phase 0 subset)

1. Copy env template: `Copy-Item .env.example .env` (then fill in values; never commit `.env`).
2. Validate repo: `powershell -ExecutionPolicy Bypass -File scripts/validate-phase0.ps1`.
3. (Optional, requires Docker): `docker compose config` to validate the stub file.
   Full `docker compose up` application stack arrives in later phases; Phase 0 Compose defines `db` only.

## Rules

- One phase at a time. See `AGENTS.md`.
- Never commit secrets, `.env`, or sandbox workspaces.
