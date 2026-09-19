# VeriReview — Agent Design

Scope: the four required agents only. No extra agents. Future agents need an ADR + owner approval.

## 1. Common Principles (All Agents)

1. **Propose, don't apply.** Agents return structured data. The Spring Boot backend applies policy, persists state, and dispatches sandbox jobs.
2. **Least context.** Each agent receives only the files/evidence it needs (scoped snapshots or references), never the whole DB, secrets, or other users' projects.
3. **Typed outputs.** Every agent output is validated against a Pydantic/JSON schema before the backend accepts it. Unparseable output = failed execution, retried ≤ N times, then marked FAILED.
4. **No shell.** Agents have no shell/build/network tools in v1. The only "tools" are read-only data accessors mediated by the backend (file fetch, diff view). Build/test run outside the agent via the sandbox dispatcher.
5. **Traceability.** Every invocation creates an `AgentExecution` row: agent type, model, prompt version, input hash, output ref, duration, status, error.
6. **Deterministic first.** Review/Coding/Verified agents always receive deterministic tool output alongside code; AI reasoning complements but never overrides sandbox evidence.
7. **Prompt versioning.** Prompts live in `ai-service/prompts/<agent>/vX.md` with changelog; execution rows record the version used.

## 2. Planning Agent

- **Goal:** convert NL requirement → structured implementation plan for user approval.
- **Input:** `requirement` text (capped length), `techPreferences` (optional, e.g. "Spring Boot 3, JPA"), `constraints` (e.g. "single module").
- **Output schema (`ProjectPlan`):** `summary`, `features[]` (name, description, acceptance criteria), `architecture` (modules, layering), `techStack` (justified choices), `entities[]` (name, fields, relations), `apis[]` (method, path, request/response sketch), `tests[]` (what will be tested per feature), `risks[]`, `estimatedScope` (S/M/L per feature — relative, not hours).
- **Graph (LangGraph):** `parse_requirement → draft_plan → self_critique (check missing entities/APIs/tests) → finalize`. Max 1 critique loop in v1.
- **Guardrails:** refuse disallowed requests (malware, credential harvesting — keyword + policy prompt, backend also validates); cap output size; never emit secrets or full app code (plan only).
- **Failure mode:** if requirement is too vague, return `needsClarification` with explicit questions instead of hallucinating — surfaced in UI as a clarification step, not a silent plan.

## 3. Review Agent

- **Goal:** produce structured, deduplicated findings from code + deterministic results. Modifies nothing.
- **Input:** project file snapshots (scoped, truncated with omission markers), `deterministicFindings[]` (ruleId, tool, file, line, message), language/framework hints.
- **Output schema (`Finding[]`):** `category` (8-enum), `severity` (5-enum), `title`, `description`, `filePath`, `lineStart/lineEnd`, `evidence` (tool rule or code quote), `source` (DETERMINISTIC → copied verbatim w/ AI enrichment in separate field; AI → newly raised), `suggestedFixHint` (short, non-binding), `confidence` (0–1, informational only — never shown as "verified").
- **Graph:** `load_context → align_deterministic (dedupe/merge same-location hits) → ai_scan (chunked per file/module, capped tokens) → merge_and_dedupe → rank (severity × confidence, deterministic first) → finalize`.
- **Guardrails:** chunk large repos (per-file token budget, skip generated/minified/vendor dirs via allowlist); cap findings per review (e.g. 100, paged); never output full file contents; path-normalize all refs (prevent traversal in output).
- **Anti-patterns forbidden:** declaring "no issues found, code is perfect" without evidence; upgrading AI findings to VERIFIED; referencing files outside the provided scope.

## 4. Coding Agent

- **Goal:** given ONE approved finding (or one plan feature in generation mode), return a minimal controlled patch.
- **Input:** `finding` (or `task`), scoped `relevantFiles[]`, repo conventions (line endings, Java version), `constraints` (max files touched, max lines changed — e.g. ≤5 files / ≤200 lines, configurable).
- **Output schema (`PatchProposal`):** `diff` (unified diff), `filesChanged[]`, `explanation` (what/why, ≤ N chars), `testsTouched[]`, `risks[]`. No prose-only answers; diff is mandatory.
- **Graph:** `load_scope → locate (identify minimal edit regions) → draft_diff → self_check (applies cleanly? scope respected? no unrelated reformatting?) → finalize`. No auto-retry that widens scope.
- **Guardrails (enforced by backend diff validator, not just prompt):**
  - Reject diffs touching paths outside project, absolute paths, `..`, symlinks, or denylisted paths (e.g. `~/.ssh`, `/etc`, workflow files unless explicitly approved task).
  - Reject new binary blobs, oversized diffs, or full-file rewrites when a surgical edit was requested.
  - Forbid claiming the fix is tested/verified — that sentence pattern fails validation.
- **Failure mode:** if the fix requires architectural changes beyond the budget, return `needsLargerScope` with rationale → UI asks user to approve expanded scope as a new FixRequest, never silently expands.

## 5. Verified Agent

- **Goal:** judge whether the Coding Agent's patch actually works, from sandbox evidence. Never trusts claims.
- **Input:** `patchRef`, `verificationRun` (build status, test summary: total/passed/failed/skipped, static-analysis delta, security-scan delta, log excerpts — truncated), `originalFinding`.
- **Output schema (`VerificationJudgment`):** `recommendation`: `VERIFIED` | `REJECTED`, `rationale` (cites specific evidence: e.g. "Test X passed, SpotBugs Y resolved, no new HIGH findings"), `residualRisks[]`, `suggestedNextStep` (optional).
- **Graph:** `load_evidence → check_gates (build green? target tests passed? no new CRITICAL/HIGH deterministic findings? finding-specific check addressed?) → judge → finalize`.
- **Decision gates (backend-enforced, agent must cite):**
  1. Build succeeded in sandbox (matching run ID).
  2. Relevant tests ran and passed (not "0 tests ran" — zero-test pass is REJECTED unless finding is docs/style with justification).
  3. No new CRITICAL/HIGH deterministic findings introduced (diff of before/after tool results).
  4. Original finding's reproduction/check (test or rule) now clean.
  - Any gate fails → REJECTED. The backend refuses to persist VERIFIED if the linked run contradicts the recommendation (defense in depth).
- **Failure mode:** inconclusive evidence → REJECTED with `needsRerun` reason, never a generous VERIFIED.

## 6. Orchestration & State

- LangGraph per agent; no mega-graph spanning all four in v1 (each invoked independently by backend; cross-agent flow is backend job state, keeping Java authoritative).
- Idempotency: backend passes `idempotencyKey` (reviewId / fixRequestId / verificationRunId); AI retries reuse the key.
- Timeouts: per-agent LLM timeout + overall execution timeout; retries only on transient errors (429/5xx/timeout), max 2, with backoff. Deterministic validation errors are not retried blindly.
- Model config: per-agent model + temperature pinned in config (e.g. planning/review lower temperature than coding); overridable via env for evals. Exact defaults chosen in Phase 7 and recorded.

## 7. Evals (Phase 7+, Expanded Phase 11)

- Golden set: small Java fixtures with known bugs (NPE, SQLi string concat, missing test, perf anti-pattern). Each eval asserts: finding recalled with correct category/severity/file, patch applies, sandbox gates behave, verified judgment matches ground truth.
- Regression rule: prompt/model changes must pass evals before merge.

## 8. What Is Explicitly NOT Built Now

- No autonomous multi-file refactor agent, no PR-writing agent, no chat agent.
- No MCP tool server (Phase 14), no RAG retriever (Phase 15) — agents receive explicitly scoped snapshots until those phases prove their worth.
