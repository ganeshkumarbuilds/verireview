# ADR-005 — AI Proposes, Backend Disposes; Backend-Enforced Verification

Status: Accepted (Phase 0, owner-approved). Non-negotiable invariant.

## Context

The core philosophy ("AI should NOT simply say code is correct") requires that verification be independent of agent claims.

## Decision

1. The Python AI service **proposes actions and produces agent results only. It must NOT directly write to PostgreSQL.** All persistence flows through Spring Boot (approval gates, sandbox dispatch, verdict persistence), with the AI service returning versioned JSON via `/internal/*`.
2. **Verification is backend-enforced.** A `VERIFICATION_RUN` may result in `VERIFIED` **only when the configured verification policy passes**, including:
   - build passed,
   - tests passed (zero-tests-passed is REJECTED, except a documented style/docs exception),
   - no new CRITICAL findings,
   - no new HIGH findings.
3. The Verified Agent **may interpret evidence but has no unilateral authority** to mark a fix VERIFIED. The backend cross-checks the recommendation against the linked run and refuses contradictory VERIFIED verdicts.

## Consequences

- Schema + service-layer enforcement (run linkage, gate checks) in Phases 10–11.
- Every agent invocation gets an `AgentExecution` row; every verdict cites its evidence run ID.
- `DETERMINISTIC` vs `AI` vs `VERIFIED` labels stay distinct everywhere (API, DB, UI).

## Verification

- Phases 10–11: eval matrix (pass→VERIFIED; fail / zero-tests / new CRITICAL-HIGH / contradictory recommendation → REJECTED or backend override), covered by tests.
