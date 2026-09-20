"""Internal agent API (backend → AI). Service-to-service auth (shared secret)
is decided in Phase 7; this validates schemas and runs the Review/Verified Agents.
Without an API key the endpoint degrades to deterministic echo (same
schema, zero AI findings) — never a network call without credentials."""

from fastapi import APIRouter, HTTPException

from app.agents.coding_agent import CodingAgentConfig, PROMPT_VERSION as CODING_PROMPT_VERSION
from app.agents.generation_agent import (
    GenerationAgentConfig,
    PROMPT_VERSION as GENERATION_PROMPT_VERSION,
)
from app.agents.review_agent import PROMPT_VERSION, ReviewAgentConfig
from app.agents.verified_agent import VerifiedAgentConfig, PROMPT_VERSION as VERIFIED_PROMPT_VERSION, run_verified_agent
from app.config import get_settings
from app.graphs.coding_graph import build_coding_graph
from app.graphs.generation_graph import build_files_graph, build_plan_graph
from app.graphs.review_graph import align_key, build_review_graph, snapshot_sizes
from app.llm import LLMProvider, NullProvider, OpenRouterProvider
from app.schemas.coding import CodingRequest, CodingResult
from app.schemas.generation import (
    FilesRequest,
    FilesResult,
    GeneratedFile,
    PlannedFile,
    PlanRequest,
    PlanResult,
)
from app.schemas.review import ProposedFinding, ReviewRequest, ReviewResult
from app.schemas.verification import VerifyRequest, VerifyResult

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


def _generation_provider(api_key: str, base_url: str | None) -> LLMProvider:
    """Per-request provider for generation (user-supplied credentials).

    The key lives in memory only for the call. An absent key is a
    controlled failure — never a silent offline draft.
    """
    if not api_key.strip():
        raise ValueError("generation requires an AI API key")
    settings = get_settings()
    return OpenRouterProvider(
        api_key=api_key,
        base_url=(base_url or "").strip() or settings.openrouter_base_url,
        timeout_seconds=settings.llm_timeout_seconds,
    )


def _generation_config(model: str) -> GenerationAgentConfig:
    return GenerationAgentConfig(
        model=(model or "").strip() or get_settings().openrouter_model,
        prompt_version=GENERATION_PROMPT_VERSION,
    )


def _verified_config(model: str) -> VerifiedAgentConfig:
    return VerifiedAgentConfig(
        model=(model or "").strip() or get_settings().openrouter_model,
        prompt_version=VERIFIED_PROMPT_VERSION,
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


@router.post("/generate/plan", response_model=PlanResult)
def run_generate_plan(request: PlanRequest) -> PlanResult:
    """Validate a generation-planning invocation and run the plan graph."""
    try:
        provider = _generation_provider(request.api_key, request.base_url)
        graph = build_plan_graph(
            provider=provider, config=_generation_config(request.model)
        )
        final_state = graph.invoke(
            {
                "generation_id": request.generation_id,
                "request": request.model_dump(mode="json"),
                "prompt_version": GENERATION_PROMPT_VERSION,
                "notes": [],
                "files": [],
            }
        )
        if final_state.get("error"):
            raise ValueError(final_state["error"])
        files = [
            PlannedFile.model_validate(item) for item in final_state.get("files", [])
        ]
        if not files:
            raise ValueError("generation planning produced no files")
        sections = final_state.get("sections", {}) or {}
    except Exception as exc:  # guard runtime; never leak credentials
        raise HTTPException(status_code=500, detail=f"generate plan failed: {exc}") from exc
    notes = [n for n in final_state.get("notes", []) if isinstance(n, str)]
    return PlanResult(
        generation_id=request.generation_id,
        files=files,
        architecture=str(sections.get("architecture", "")),
        dependencies=[str(d) for d in sections.get("dependencies", [])],
        directories=[str(d) for d in sections.get("directories", [])],
        apis=str(sections.get("apis", "")),
        steps=[str(s) for s in sections.get("steps", [])],
        notes="; ".join(notes[-2:]),
    )


@router.post("/generate/files", response_model=FilesResult)
def run_generate_files(request: FilesRequest) -> FilesResult:
    """Validate a generation invocation and run the files graph."""
    try:
        provider = _generation_provider(request.api_key, request.base_url)
        graph = build_files_graph(
            provider=provider, config=_generation_config(request.model)
        )
        final_state = graph.invoke(
            {
                "generation_id": request.generation_id,
                "request": request.model_dump(mode="json"),
                "prompt_version": GENERATION_PROMPT_VERSION,
                "notes": [],
                "files": [],
            }
        )
        if final_state.get("error"):
            raise ValueError(final_state["error"])
        files = [
            GeneratedFile.model_validate(item) for item in final_state.get("files", [])
        ]
        if not files:
            raise ValueError("generation produced no files")
    except Exception as exc:  # guard runtime; never leak credentials
        raise HTTPException(status_code=500, detail=f"generate files failed: {exc}") from exc
    notes = [n for n in final_state.get("notes", []) if isinstance(n, str)]
    return FilesResult(
        generation_id=request.generation_id,
        files=files,
        notes="; ".join(notes[-2:]),
    )


@router.post("/verify", response_model=VerifyResult)
async def run_verify(request: VerifyRequest) -> VerifyResult:
    """Validate a verification invocation and run the Verified Agent graph."""
    try:
        provider = OpenRouterProvider(
            api_key=get_settings().openrouter_api_key,
            base_url=get_settings().openrouter_base_url,
            timeout_seconds=get_settings().llm_timeout_seconds,
        )
        result = await run_verified_agent(
            provider=provider,
            config=_verified_config(request.model),
            request=request,
        )
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"verify failed: {exc}") from exc
    return result
