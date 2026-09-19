# ADR-004 — Modular Monolith + Separate Python AI Service

Status: Accepted (Phase 0, owner-approved).

## Context

`docs/ARCHITECTURE.md` compared microservices / modular monolith + Python AI service / full monolith, with owner preference for the middle option.

## Decision

- Primary backend: **Spring Boot modular monolith** (`backend/`, Java 21, modules per `ARCHITECTURE.md` §3).
- Separate **`ai-service/`**: Python 3.12 + FastAPI + LangGraph for Planning/Review/Coding/Verified graphs.
- Frontend: React + TypeScript + Vite + Tailwind, calling the backend only.
- Primary database: PostgreSQL 16, owned by Spring Boot.
- No microservices without a new ADR + owner approval.

## Consequences

- Cross-module calls via service interfaces; no cross-aggregate repository sharing.
- AI orchestration churn (prompts, SDKs, evals) is isolated from the enterprise backend.
- Async in v1 = Spring async + job polling; no Kafka (see ADR-008).

## Verification

- Phase 1: module package layout matches `ARCHITECTURE.md` §3; Phase 7: AI service exposes only versioned `/internal/*` endpoints.
