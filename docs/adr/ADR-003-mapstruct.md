# ADR-003 — MapStruct for Entity↔DTO Mapping

Status: Accepted (Phase 0, owner-approved).

## Context

`docs/ARCHITECTURE.md` §3 required DTOs at every API boundary with JPA entities never serialized, but left the mapper choice open.

## Decision

- **MapStruct** (annotation-processed, compile-time mappers) for entity↔DTO conversion.
- Manual mapping allowed only for cases MapStruct cannot express cleanly (documented inline).

## Consequences

- Mapper interfaces live beside their domain module; no reflection-based mapping by default.
- Build must enable annotation processing (Maven compiler config, Phase 1).

## Verification

- Phase 1+: mapper unit tests; CI fails on unmapped-target warnings if enabled.
