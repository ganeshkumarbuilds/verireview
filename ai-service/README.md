# ai-service/ — Python agentic service (Phase 7A foundation)

FastAPI + LangGraph review skeleton. **Proposes only**: no PostgreSQL access,
no file writes, no code execution (ADR-005, AGENTS.md rules 7/14). The Spring
Boot backend owns all business state; this service receives scoped data and
returns versioned JSON. No LLM calls are made in 7A.

Rule: frontend calls the Spring Boot backend only — never this service
directly (internal API, no browser docs).

## Prerequisites

- Python 3.12+ (`python --version`; developed against 3.14 here, code targets 3.12 syntax)
- No system packages beyond a venv

## Setup & run

```powershell
cd ai-service
python -m venv .venv
.\.venv\Scripts\python -m pip install -e ".[test]"
.\.venv\Scripts\python -m pytest            # 21 tests, no network
.\.venv\Scripts\python -m uvicorn app.main:app --host 127.0.0.1 --port 8001
# GET http://127.0.0.1:8001/health → {"status":"UP", ...}
```

`python -m compileall .` passes (no syntax errors outside the venv).

## Configuration (environment only, never code)

| Variable | Default | Purpose |
|----------|---------|---------|
| `HOST` / `PORT` | `127.0.0.1` / `8001` | Bind address |
| `OPENROUTER_API_KEY` | _(empty = LLM disabled)_ | Model access (unused in 7A) |
| `OPENROUTER_BASE_URL` | `https://openrouter.ai/api/v1` | OpenAI-compatible endpoint |
| `OPENROUTER_MODEL` | `openrouter/auto` | Per-agent override in later phases |
| `LLM_TIMEOUT_SECONDS` | `60` | Per-call timeout |
| `BACKEND_INTERNAL_URL` | `http://localhost:8080` | Backend callbacks (Phase 7+) |
| `AI_SERVICE_INTERNAL_SECRET` | _(empty)_ | `/internal/*` shared secret (Phase 7+) |

## Layout

- `app/main.py` — FastAPI factory; `GET /health`; mounts `/internal/*`
- `app/config.py` — `Settings` (pydantic-settings) + process singleton
- `app/schemas/review.py` — `ReviewRequest` / `ProposedFinding` / `ReviewResult`
- `app/graphs/review_graph.py` — `load_context → align_deterministic →
  ai_scan → merge_rank → finalize` (ai_scan degrades to zero AI findings
  without a provider; merge dedupes, ranks deterministic-first, caps at 100)
- `app/agents/review_agent.py` — reasoning: versioned prompt rendering,
  single LLM call, strict JSON parsing (malformed output degrades, never
  raises), severity/category/path normalization, deterministic echo +
  merge/rank (Phase 7B)
- `app/llm/__init__.py` — `LLMProvider` ABC, `FakeProvider`, `NullProvider`
  (offline fallback), `OpenRouterProvider` (mock-transport tested; live calls
  only with a configured key)
- `app/prompts/review/v1.md` — versioned prompt (`review/v1`)
- `app/api/internal.py` — `POST /internal/review` (same contract: validates,
  runs the agent, returns deterministic echoes + AI findings; without an API
  key the result is deterministic-only)
- `tests/` — config, schemas, graph, agent reasoning, LLM, health/API tests
  (`FakeProvider` only — no network, ever)

## Dependencies (pinned in `pyproject.toml`)

`fastapi`, `uvicorn`, `langgraph` (+ `langchain-core`), `pydantic`,
`pydantic-settings`, `httpx`, `pytest`. Nothing else — no DB drivers, no
GitHub/RAG/MCP/queue libraries (their roadmap phases only).
