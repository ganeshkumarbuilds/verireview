# ADR-006 — Public GitHub Import for v1, OAuth Deferred

Status: Accepted (Phase 0, owner-approved).

## Context

`docs/API_DESIGN.md` and the roadmap Phase 5/12 needed an import scope decision: public-clone now vs OAuth immediately.

## Decision

- Initial GitHub support (Phase 5): **public repository import only** — allowlisted `https://github.com/<owner>/<repo>(.git)?`, shallow `clone --depth 1`, branch allowlist, size cap, arg-array execution, timeout, quarantine.
- **GitHub OAuth deferred to Phase 12**, with minimal scopes, encrypted token storage, and webhook signature verification.

## Consequences

- Private repos return `422` until Phase 12 (documented in OpenAPI).
- SSRF controls (domain allowlist, no private-IP redirects) apply from Phase 5.

## Verification

- Phase 5: URL-allowlist + branch-charset + size-cap tests; private-URL rejection test.
