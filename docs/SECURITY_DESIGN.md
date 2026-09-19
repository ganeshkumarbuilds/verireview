# VeriReview — Security Design

Security is a headline feature. This document is the threat-model + control plan. Implementation follows the roadmap; nothing here executes user code on the host.

## 1. Threat Model (Top Risks)

| # | Threat | Impact | Where mitigated |
|---|--------|--------|-----------------|
| T1 | Malicious ZIP (ZipSlip, symlink escape, ZIP bomb) | Host overwrite, DoS | Ingestion validation + jailed extraction (Phase 5) |
| T2 | Path traversal via `path=` params or diff paths | Read/overwrite arbitrary files | Normalization + jail checks on every file access + diff validator (Phases 5, 9) |
| T3 | Command injection via repo URL / filenames / patch content passed to shell | RCE | No shell interpolation: ProcessBuilder arg arrays only, allowlists, sandbox-only execution (Phases 5, 10, 16) |
| T4 | SSRF via GitHub URL / webhook | Internal network probe | URL allowlist (github.com only in v1), no redirects to private IPs, egress-restricted sandbox (Phases 5, 12, 16) |
| T5 | Uploaded code134 RCE/cryptominer escapes sandbox | Host compromise | Docker isolation: no `--privileged`, dropped caps, read-only rootfs where possible, CPU/mem/time caps, no network (or allowlisted), cleanup always (Phases 10, 16) |
| T6 | Auth bypass / IDOR (user A reads user B project) | Data leak | Ownership checks on every project-scoped query + tests; 404-not-403 for enumeration resistance (Phase 3) |
| T7 | JWT theft / weak passwords / session fixation | Account takeover | BCrypt, short-lived access + rotated refresh, secure cookie/header handling, rate-limited auth (Phase 3) |
| T8 | Agent prompt injection via malicious repo content | Exfiltration / wrong fixes | Agents are read-scoped, cannot call network/shell; outputs schema-validated; secrets never placed in agent context (Phases 7–11) |
| T9 | Secrets in code/logs/images | Credential leak | Env/secret-manager only; log redaction; `.dockerignore` + secret scanning in CI (Phases 0, 18) |
| T10 | DoS via large uploads / infinite builds / LLM spend | Availability / cost blowout | Size caps, timeouts, per-user rate limits + quotas, LLM token budgets (Phases 5, 10, 19) |
| T11 | Supply chain (vulnerable deps, poisoned images) | Inherited CVE | Pinned versions, lockfiles, OWASP scan in CI, minimal base images (Phases 6, 18) |
| T12 | Audit evasion / repudiation | Untraceable abuse | Append-only audit log on auth, ingestion, review, fix, verify, admin actions (Phase 1+) |

## 2. Authentication & Authorization

- Spring Security filter chain: public (`/auth/*`, `/actuator/health`, docs in dev), authenticated-everything-else; ADMIN-only paths (`/admin/*`, full Actuator).
- Passwords: BCrypt (strength 12 default), min length 8, reject top-breached list (simple denylist in v1).
- JWT: short-lived access (e.g. 15 min), rotated refresh (e.g. 7 d, reuse detection invalidates chain). Algorithm allowlisted (HS256/RS256 — one only, decided Phase 3); keys from env, never code.
- RBAC: `@PreAuthorize` / service-level ownership checks (both — belt and suspenders). Every project-scoped repository query includes `owner_id` predicate; missing check fails code review.
- Frontend stores tokens in memory (preferred) — exact storage decided Phase 4 with XSS trade-off note; never in URL.

## 3. Input Validation & File Safety

- Bean Validation on all DTOs + service-level allowlists (URL, branch, filename charset, language enum).
- ZIP pipeline: extension+MIME+magic bytes → entry scan (count, uncompressed size, no `..`/absolute/symlink, per-file cap) → extract to fresh quarantine dir with canonical-path jail check per entry → hash + inventory → move to project store. Failure deletes quarantine.
- Paste pipeline: filename sanitized, language enum-checked, content size-capped, stored as single `project_files` row.
- File viewer: `path` normalized (`Path.normalize`), must `startsWith(projectDir)`; size-capped reads; binary detection → refuse render.
- Diff validator (Phase 9): unified-diff parse, per-file path jail, caps on files/lines/size, binary rejection, denylist (e.g. `*.key`, `*.pem`, `.git/**`, CI workflows unless task explicitly allows).

## 4. Execution Isolation (Sandbox)

- Uploaded/generated code NEVER runs in the Spring Boot JVM, never via frontend, never via AI service shell.
- v1 (Phase 10): backend dispatches to `sandbox-runner` (separate container/profile) that: copies project snapshot → applies patch → `mvn -B -DskipTests=false verify` (or equivalent) + analysis tools → captures exit codes, test XML, log tail → returns structured result → destroys container/workspace.
- Hardening (Phase 16): `--network=none` (or allowlisted Maven Central proxy), `--pids-limit`, `--memory`, `--cpus`, read-only mounts + tmpfs scratch, seccomp/AppArmor defaults, timeout kill (e.g. 10 min), workspace wipe verified post-run.
- Resource quotas per user (concurrent runs cap) to prevent miner/DoS abuse.

## 5. GitHub Boundary (Pre-Phase-12 Posture)

- v1 import = unauthenticated `git clone --depth 1 <allowlisted-URL>` executed by backend-controlled process (arg array, no shell), timeout + size cap, in sandbox-adjacent quarantine — not in web request thread.
- No webhooks / no OAuth tokens stored until Phase 12. When added: minimal scopes, encrypted token storage, webhook signature verification, per-repo access checks.

## 6. Agent & LLM Safety

- No secrets, tokens, or other users' data in agent context builders (unit-tested allowlist of context fields).
- Tool surface in v1 is read-only data; build/test/security tools are backend-dispatched, not agent-invoked (MCP in Phase 14 formalizes this with explicit permission manifests).
- Prompt-injection handling: treat all project content as untrusted data (delimit + instruct to ignore embedded instructions); findings quoting code are escaped in UI (React default escaping + no `dangerouslySetInnerHTML` for code).
- LLM spend caps: per-request token budgets, per-user daily quotas; all calls logged with model + tokens in `agent_executions`.

## 7. Secrets, Logging, Data Protection

- Secrets only via env / Docker secrets / CI secrets; `git-secrets`/gitleaks scan in CI; `.env` never committed (`.env.example` only).
- Logs: no passwords, tokens, code secrets, or full diffs at INFO; structured logs with traceId; error responses generic to client, detailed server-side.
- PII: minimal (email, display name); GDPR-friendly delete path (account deletion anonymizes audit actor refs, preserves trail integrity — designed Phase 3).
- Transport: TLS terminated at reverse proxy in prod (Compose-local may be HTTP with documented warning); security headers (CSP, HSTS, X-Content-Type-Options) set by backend/proxy.

## 8. Rate Limiting & Abuse Controls (Mechanism deferred, policy now)

- Tiers: auth (strictest), ingestion/review/fix triggers (strict), reads (lenient). `429 + Retry-After` everywhere.
- Implementation starts as in-memory bucket (single instance) with Redis migration trigger defined in `TECH_STACK.md` — interface-first so swap is clean.

## 9. Security Testing Requirements

- Phase 3: auth tests (IDOR matrix, expired/tampered JWT, RBAC unit + MVC slice tests).
- Phase 5: ingestion attack fixtures (ZipSlip zip, symlink zip, bomb-shape zip, traversal `path=` cases) — all must be rejected with 4xx + audit entry.
- Phase 9–10: malicious-diff fixtures (absolute/`..`/oversized/binary) rejected; sandbox escape-attempt commands never reach host shell (arg-array + allowlist tests).
- Phase 18: OWASP Dependency-Check + secret scan + container scan in CI; pipeline fails on CRITICAL.
