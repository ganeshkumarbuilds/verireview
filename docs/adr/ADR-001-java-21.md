# ADR-001 — Java 21 LTS

Status: Accepted (Phase 0, owner-approved).

## Context

`docs/TECH_STACK.md` left the Java version as "17 or 21 (TBD Phase 0)". The backend is the primary enterprise artifact (Spring Boot, JPA, Security) and must use a modern LTS.

## Decision

- **Java 21 LTS** for `backend/` (toolchain + CI pin).
- Spring Boot **4.1.1**, build tool **Maven**.
- Phase 1 records exact distribution/vendor (e.g. Temurin) in `backend/pom.xml` + CI.

## Consequences

- Enables Java 21 features (records, pattern matching, sequenced collections; virtual threads available for later async work).
- Requires JDK 21 in dev environments, CI images, and backend/sandbox Dockerfiles.
- No Java 17 compatibility shims.

## Verification

- Phase 1: `java -version` shows 21 in CI log; `mvn -B verify` compiles with `release 21`.
