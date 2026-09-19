# AGENTS.md — Permanent Development Rules for VeriReview

These rules bind all future implementation work in this repository. They restate and operationalize the user's constraints. Conflicts: this file wins unless the user explicitly overrides in-chat for a specific phase.

## 1. Planning Before Code

1. Do not implement until the current phase's objective, files, and verification criteria are agreed.
2. Prefer small, reviewable changes. Keep the project buildable after every phase (`mvn verify`, `npm test`, `pytest` green as applicable).
3. Never silently change requirements. Ambiguity → ask via questions tool before deciding. Record decisions as ADRs for: Java version, migration tool, mapper, JWT strategy, git strategy, Kafka-need, RAG-need.

## 2. Architecture Discipline

4. Stack: Spring Boot **modular monolith** + separate Python **agentic AI service** + React SPA + PostgreSQL. No microservices without an ADR + owner approval.
5. Module rules: thin controllers → service-owned `@Transactional` boundaries → Spring Data repositories → MapStruct/manual mappers. No business logic in controllers. No cross-aggregate repository sharing.
6. DTOs at every API boundary. **Never serialize JPA entities.** Separate `*Request`/`*Response` types with Bean Validation on inputs.
7. Java backend owns business truth and persistence. AI service proposes; backend disposes (approval gates, sandbox dispatch, verdict persistence). No duplicated business logic between Java and Python.
8. Async only where justified. Kafka/Redis/MCP/RAG arrive only at their roadmap phases **and** only when their trigger conditions are met — otherwise document why they were skipped.
9. No resume-driven tech. Every dependency must justify itself in `TECH_STACK.md` or the phase plan.

## 3. Verification-First (Non-Negotiable)

10. Never present an AI fix as correct on the Coding Agent's word. `VERIFIED` requires a linked `VerificationRun` (build green + relevant tests passed + no new CRITICAL/HIGH findings). Zero-tests-passed is REJECTED (except documented style/docs exception).
11. Distinguish `DETERMINISTIC` vs `AI` vs `VERIFIED` findings everywhere (API, DB, UI). Never relabel AI as VERIFIED without the gate in rule 10.
12. Every agent invocation → `AgentExecution` row (agent, model, prompt version, input hash, status, duration). Every verdict cites its evidence run ID.
13. Never delete or rewrite findings/tests to make a check pass. Inconclusive evidence → REJECTED with reason, never generous VERIFIED.

## 4. Security (Secure by Default, Fail Safely)

14. Uploaded/generated code **never** executes in the Spring Boot JVM, frontend, or AI-service shell. Only the Docker sandbox dispatcher runs it, with timeout + CPU/mem caps + cleanup.
15. No shell interpolation of user input. `ProcessBuilder` arg arrays only; allowlists for URLs, branches, filenames, diff paths. Canonical-path jail check on every file access.
16. Validate ZIPs (count/size/symlink/traversal/bomb-shape) before extraction; extract to quarantine; audit rejections.
17. No secrets in code, logs, images, or prompts. Env/secret-manager only. Redact logs. Secret scan must pass in CI.
18. Auth: BCrypt, short-lived access + rotated refresh, RBAC ownership checks on every project-scoped query (non-owner → 404 to resist enumeration). Rate-limit auth/ingestion/trigger endpoints; `429 + Retry-After`.
19. Agents get least-privilege, read-scoped context only — no shell, no network, no secrets, no cross-user data. Treat repo content as untrusted (prompt-injection delimiting). No `dangerouslySetInnerHTML` for code/evidence.
20. No destructive change without explicit user approval (FixRequest gate, plan-approval gate, WONTFIX reason). Default to minimal diffs; forbid full rewrites when surgical edits suffice.

## 5. Code & Testing Standards

21. Java: modern LTS (per ADR-001), SOLID, layered architecture, pagination/filter/sort on all user lists, Flyway/Liquibase migrations (additive only), OpenAPI documented, structured logging with trace IDs.
22. Every major feature ships with tests: backend (JUnit5/Mockito/Test slices/Testcontainers), frontend (Vitest/RTL), AI (pytest graph/tool/eval tests). Target ≥70% backend domain/service line coverage; pipeline fails below agreed gate.
23. Attack fixtures are first-class tests: ZipSlip/symlink/bomb/traversal, malicious diffs (`..`/absolute/binary/oversized), tampered JWTs, IDOR matrix, webhook-signature negatives, sandbox timeout/cleanup.
24. Do not modify unrelated files. Do not delete functionality to make a test pass. Fix forward or ask.

## 6. Workflow & Hygiene

25. One phase at a time. End each phase with: what changed, how verified (commands + outputs), residual risks, and explicit DoD checklist result. Then STOP and wait for instruction.
26. Keep diffs reviewable: separate refactor from feature; update docs (`docs/*`, OpenAPI, ADRs) in the same change that introduces behavior.
27. Commit discipline: inspect `git status/diff/log` before committing; stage only intended files; concise messages matching repo style. Never commit secrets, `.env`, ZIP fixtures with real credentials, or sandbox workspaces.
28. Frontend never calls AI-service/sandbox directly. AI-service never writes the primary DB directly (via backend callbacks only). File blobs via storage abstraction (local volume in v1), never inline beyond capped threshold.
29. Observability from the start: durations for review/agent/build/verify, job-failure counters, API latency; health endpoints; trace IDs across backend→AI→sandbox hops.
30. Cost awareness: sandbox quotas, LLM token budgets, minimal AWS design. No expensive-by-default infrastructure.

## 7. Definition of Done (Every Phase)

- [ ] Objective met, no scope creep beyond phase file list.
- [ ] `mvn -B verify` / `npm test` / `pytest` (as applicable) green; new tests cover happy + negative + attack cases.
- [ ] OpenAPI/docs/ADRs updated; no entity leaks; no secrets added.
- [ ] Verification criteria from DEVELOPMENT_ROADMAP demonstrated (logs/URLs/outputs quoted).
- [ ] `docker compose up` still yields a working system (or documented phase-limited subset).
- [ ] STOP and report; await next-phase instruction.
