# ADR-009 — JWT Authentication Strategy (Phase 3)

Status: Accepted (Phase 3).

## Context

`docs/SECURITY_DESIGN.md` §2 requires a JWT strategy decision in Phase 3:
one allowlisted algorithm, short-lived access + rotated refresh with reuse
detection, keys from env. `docs/DATABASE_DESIGN.md` §2 notes the
refresh-token table is deliberately absent unless Phase 3 decides otherwise.

## Decision

- **Algorithm: HS256 only** (single allowlisted algorithm; no `none`, no
  algorithm confusion — parser is pinned to the HS256 signing key).
- **Access tokens**: 15 minutes, stateless, carry `sub` (user id), `email`,
  `roles`, `type=access`.
- **Refresh tokens**: 7 days, `type=refresh` + `jti`. Rotation is tracked in
  an **in-memory allowlist** (`RefreshTokenStore`, single-instance); no new
  database table in Phase 3. Reuse of a revoked refresh token revokes the
  whole token family (reuse detection) and returns 401.
- **Secret**: `JWT_SIGNING_KEY` env var only (min 256 bits for HS256); the
  backend fails fast at startup when it is missing or too short.
- **Passwords**: BCrypt strength 12, minimum length 12, small breached-password
  denylist (v1).
- **Rate limiting**: in-memory fixed-window filter on `/api/v1/auth/*`
  (strictest tier), `429 + Retry-After` on exceed; Redis migration trigger
  stays as defined in `TECH_STACK.md`.

## Consequences

- Sessions do not survive a backend restart (in-memory refresh store).
  Acceptable for single-instance Phase 3; persisting refresh sessions becomes
  a Phase 13+/19 concern alongside distributed rate limiting.
- One JWT library only: `io.jsonwebtoken:jjwt` 0.12.6 (api + impl + jackson).

## Verification

- Phase 3: `.\mvnw.cmd -B verify` green, including register/login/refresh/
  logout happy paths, expired/tampered JWT rejection, refresh reuse detection,
  RBAC (ADMIN-only `/users`), and audit-row assertions.
