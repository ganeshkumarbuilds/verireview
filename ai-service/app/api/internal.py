"""Internal agent API (backend → AI). Service-to-service auth (shared secret)
is decided in Phase 7; this validates schemas and runs the Review Agent.
Without an API key the endpoint degrades to deterministic echo (same
schema, zero AI findings) — never a network call without credentials."""

from fastapi import APIRouter, HTTPException

from app.agents.coding_agent import CodingAgentConfig, PROMPT_VERSION as CODING_PROMPT_VERSION
from app.agents.review_agent import PROMPT_VERSION, ReviewAgentConfig
from app.config import get_settings
from app.graphs.coding_graph import build_coding_graph
from app.graphs.review_graph import align_key, build_review_graph, snapshot_sizes
from app.llm import LLMProvider, NullProvider, OpenRouterProvider
from app.schemas.coding import CodingRequest, CodingResult
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


def _coding_provider() -> LLMProvider:
    settings = get_settings()
    if settings.llm_enabled:
        return OpenRouterProvider(
            api_key=settings.openrouter_api_key,
            base_url=settings.openrouter_base_url,
            timeout_seconds=settings.llm_timeout_seconds,
        )
    return NullProvider()


def _coding_config() -> CodingAgentConfig:
    settings = get_settings()
    return CodingAgentConfig(
        model=settings.openrouter_model,
        prompt_version=CODING_PROMPT_VERSION,
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


@router.post("/coding", response_model=CodingResult)
@router.post("/code-fix", response_model=CodingResult, include_in_schema=False)
def run_coding(request: CodingRequest) -> CodingResult:
    """Validate a coding invocation and run the Coding Agent graph."""
    try:
        graph = build_coding_graph(provider=_coding_provider(), config=_coding_config())
        final_state = graph.invoke(
            {
                "fix_request_id": request.fix_request_id,
                "finding": request.finding.model_dump(mode="json"),
                "scope_note": request.scope_note,
                "language": request.language,
                "files": [f.model_dump(mode="json") for f in request.files],
                "prompt_version": CODING_PROMPT_VERSION,
                "notes": [],
                "diff": "",
                "explanation": "",
                "files_changed": 0,
                "additions": 0,
                "deletions": 0,
            }
        )
        if final_state.get("error"):
            raise ValueError(final_state["error"])
        diff = str(final_state.get("diff", "")).strip()
        if not diff:
            raise ValueError("coding agent produced empty diff")
        explanation = str(final_state.get("explanation", "")).strip()
        files_changed = int(final_state.get("files_changed", 0) or 0)
        additions = int(final_state.get("additions", 0) or 0)
        deletions = int(final_state.get("deletions", 0) or 0)
    except Exception as exc:  # guard runtime
        raise HTTPException(status_code=500, detail=f"coding graph failed: {exc}") from exc
    notes = [n for n in final_state.get("notes", []) if isinstance(n, str)]
    return CodingResult(
        fix_request_id=request.fix_request_id,
        agent="coding",
        prompt_version=str(final_state.get("prompt_version", CODING_PROMPT_VERSION)),
        diff=diff,
        files_changed=files_changed,
        additions=additions,
        deletions=deletions,
        explanation=explanation,
        notes="; ".join(notes[-2:]),
    )
