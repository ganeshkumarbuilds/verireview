"""Internal agent API (backend → AI). Service-to-service auth (shared secret)
is decided in Phase 7; this validates schemas and runs the Review Agent.
Without an API key the endpoint degrades to deterministic echo (same
schema, zero AI findings) — never a network call without credentials."""

from fastapi import APIRouter, HTTPException

from app.agents.review_agent import PROMPT_VERSION, ReviewAgentConfig
from app.config import get_settings
from app.graphs.review_graph import align_key, build_review_graph, snapshot_sizes
from app.llm import LLMProvider, NullProvider, OpenRouterProvider
from app.schemas.review import ProposedFinding, ReviewRequest, ReviewResult

router = APIRouter(prefix="/internal", tags=["internal"])


def _provider() -> LLMProvider:
    settings = get_settings()
    if settings.llm_enabled:
        return OpenRouterProvider(
            api_key=settings.openrouter_api_key,
            base_url=settings.openrouter_base_url,
            timeout_seconds=settings.llm_timeout_seconds,
        )
    return NullProvider()


def _config() -> ReviewAgentConfig:
    settings = get_settings()
    return ReviewAgentConfig(
        model=settings.openrouter_model,
        prompt_version=PROMPT_VERSION,
    )


@router.post("/review", response_model=ReviewResult)
def run_review(request: ReviewRequest) -> ReviewResult:
    """Validate a review invocation and run the Review Agent graph."""
    try:
        aligned = [align_key(hit) for hit in request.deterministic_findings]
        graph = build_review_graph(provider=_provider(), config=_config())
        final_state = graph.invoke(
            {
                "review_id": request.review_id,
                "project_id": request.project_id,
                "file_count": snapshot_sizes(request.files),
                "deterministic_count": len(request.deterministic_findings),
                "aligned_keys": aligned,
                "prompt_version": PROMPT_VERSION,
                "notes": [],
                "files": [f.model_dump(mode="json") for f in request.files],
                "deterministic": [
                    h.model_dump(mode="json") for h in request.deterministic_findings
                ],
                "ai_findings": [],
                "merged_findings": [],
            }
        )
        findings = [
            ProposedFinding.model_validate(item)
            for item in final_state.get("merged_findings", [])
        ]
    except Exception as exc:  # graph construction is tested; guard runtime anyway
        raise HTTPException(status_code=500, detail=f"review graph failed: {exc}") from exc
    notes = [n for n in final_state.get("notes", []) if isinstance(n, str)]
    return ReviewResult(
        review_id=request.review_id,
        agent="review",
        prompt_version=str(final_state.get("prompt_version", PROMPT_VERSION)),
        findings=findings,
        notes="; ".join(notes[-3:]),
    )
