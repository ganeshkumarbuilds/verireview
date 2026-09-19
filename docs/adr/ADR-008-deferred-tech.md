# ADR-008 — Deferred Tech: OAuth, Kafka, Redis, MCP, RAG, Hardening, Observability, AWS

Status: Accepted (Phase 0, owner-approved).

## Context

Owner explicitly deferred these to later phases to avoid premature complexity. Each has a trigger condition in `docs/TECH_STACK.md` §7 / roadmap.

## Decision

Deferred (do NOT introduce before their phase, and then only when the trigger holds; otherwise document the skip):

- GitHub OAuth → Phase 12 (trigger: demand for private repos / PR workflows).
- Kafka → Phase 13 (trigger: multi-instance durability / retry / ordering need).
- Redis → Phase 13+ (trigger: measured hot path or distributed rate limiting).
- MCP tools → Phase 14 (trigger: ≥2 tools needing standardized permissioned invocation).
- RAG / pgvector → Phase 15 (trigger: evals show retrieval beats scoped snapshots on large repos).
- Advanced sandbox hardening → Phase 16 (initial limits in ADR-007 suffice until then).
- Observability stack (Prometheus/Grafana) → Phase 17 (Micrometer hooks from Phase 1+).
- AWS deployment → Phase 18+ (minimal, cost-capped design only).

## Consequences

- Reviews that sneak these in early fail review.
- Skipping a deferred item at its phase requires a written reason, not silence.

## Verification

- Phase gates: dependency manifests (`pom.xml`, `package.json`, `requirements.txt`) must NOT contain Kafka/Redis/MCP/RAG clients before their phases (checked in review).
