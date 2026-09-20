# ADR-010 — Frontend Session Persistence (Phase 13)

Status: Accepted (user-approved, Phase 13 navbar rework).

## Context

The frontend kept JWTs in React memory only (see `AuthContext`, pre-Phase 13
comment citing `SECURITY_DESIGN.md` §2). Any page reload silently dropped the
session: the navbar fell back to Login/Register and the dashboard spun on
"Loading…" forever with no token. Users expect a reload to keep them signed
in. Backend behavior (ADR-009: short-lived access + rotated refresh) is
unchanged.

## Decision

- Persist `accessToken` + `refreshToken` in `localStorage` under the
  `verireview.*` keys on login/register; clear both on logout.
- On startup, restore tokens from storage and re-fetch `GET /auth/me`; an
  invalid/expired token clears the stored session (fail safe, logged out).
- Storage access is wrapped in try/catch: when unavailable (private mode),
  the session degrades to in-memory only.
- Tests inject `initial` auth state and skip the restore path, so no test
  depends on browser storage.

## Consequences

- Reloads no longer log the user out; the navbar reliably shows the profile
  icon + username + Logout for authenticated users.
- Accepted risk (explicit user trade-off): persisted tokens are readable by
  JavaScript, so an XSS flaw would expose them — memory-only storage was
  safer on that axis. Mitigations stay as-is: no secrets in code/logs (rule
  17), no `dangerouslySetInnerHTML` for untrusted content (rule 19), short
  access-token lifetime + rotated refresh server-side (ADR-009). A future
  httpOnly-cookie session remains an option if backend auth is revisited.

## Verification

- Frontend `npm test -- --run` green (incl. logout-returns-home test) and
  `npm run build` (`tsc --noEmit && vite build`) green after the change.
