# ADR-002 — Flyway for Schema Migrations

Status: Accepted (Phase 0, owner-approved).

## Context

`docs/DATABASE_DESIGN.md` and `docs/TECH_STACK.md` required one migration tool (Flyway or Liquibase), additive-only migrations on PostgreSQL 16.

## Decision

- **Flyway** for all versioned schema migrations (`V1__init.sql` in Phase 2, additive thereafter).
- No Liquibase. Seed data limited to `USER`/`ADMIN` roles.

## Consequences

- Plain-SQL migrations, reproducible from empty DB via Testcontainers in CI.
- Destructive alters require a new ADR + backup note.

## Verification

- Phase 2: clean-DB migration run + repository slice tests green.
