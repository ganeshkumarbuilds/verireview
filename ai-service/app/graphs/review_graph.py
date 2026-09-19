"""Review Agent graph (AGENT_DESIGN §3, Phase 7B).

Pipeline: ``load_context → align_deterministic → ai_scan → merge_rank →
finalize``. State keys are additive over 7A (old payloads still run;
``ai_scan`` degrades to zero AI findings without a provider).
"""

from typing import TypedDict

from langgraph.graph import END, START, StateGraph

from app.agents.review_agent import (
    ReviewAgentConfig,
    echo_deterministic,
    merge_rank as merge_rank_findings,
    run_review_agent,
)
from app.llm import LLMProvider
from app.schemas.review import DeterministicFindingIn, FileSnapshot, ProposedFinding


class ReviewState(TypedDict, total=False):
    """Typed graph state. Keys are stable across 7A → 7B → Phase 7."""

    review_id: str
    project_id: str
    file_count: int
    deterministic_count: int
    aligned_keys: list[str]
    prompt_version: str
    notes: list[str]
    # 7B additions (serialized inputs/outputs; providers stay out of state).
    files: list[dict]
    deterministic: list[dict]
    ai_findings: list[dict]
    merged_findings: list[dict]


def load_context(state: ReviewState) -> dict:
    """Record scoped input sizes (files/finding lists live outside state)."""
    notes = list(state.get("notes", []))
    notes.append("context loaded")
    return {
        "file_count": int(state.get("file_count", 0)),
        "deterministic_count": int(state.get("deterministic_count", 0)),
        "notes": notes,
    }


def align_deterministic(state: ReviewState) -> dict:
    """Echo deterministic hits as stable merge keys (tool|rule|file|line)."""
    keys = list(state.get("aligned_keys", []))
    notes = list(state.get("notes", []))
    notes.append("deterministic hits aligned")
    return {"aligned_keys": keys, "notes": notes}


def make_ai_scan(
    provider: LLMProvider | None, config: ReviewAgentConfig
):
    """Build the ai_scan node bound to one provider/config pair."""

    def ai_scan(state: ReviewState) -> dict:
        notes = list(state.get("notes", []))
        if provider is None:
            notes.append("ai_scan skipped (no provider)")
            return {"ai_findings": [], "notes": notes}
        files = [
            FileSnapshot.model_validate(item) for item in state.get("files", [])
        ]
        deterministic = [
            DeterministicFindingIn.model_validate(item)
            for item in state.get("deterministic", [])
        ]
        findings, errors = run_review_agent(
            files, deterministic, None, provider, config
        )
        for error in errors:
            notes.append(f"ai_scan parse note: {error}")
        notes.append(f"ai_scan produced {len(findings)} AI findings")
        return {
            "ai_findings": [finding.model_dump(mode="json") for finding in findings],
            "notes": notes,
        }

    return ai_scan


def merge_rank(state: ReviewState, max_findings: int = 100) -> dict:
    """Merge deterministic echoes with AI hits: dedupe, rank, cap."""
    deterministic = [
        DeterministicFindingIn.model_validate(item)
        for item in state.get("deterministic", [])
    ]
    ai_findings = [
        ProposedFinding.model_validate(item) for item in state.get("ai_findings", [])
    ]
    merged = merge_rank_findings(
        echo_deterministic(deterministic), ai_findings, max_findings
    )
    notes = list(state.get("notes", []))
    notes.append(f"merged and ranked {len(merged)} findings")
    return {
        "merged_findings": [finding.model_dump(mode="json") for finding in merged],
        "notes": notes,
    }


def finalize(state: ReviewState) -> dict:
    """Emit the result marker with merged counts."""
    merged = state.get("merged_findings", [])
    deterministic = sum(
        1 for item in merged if item.get("source") == "DETERMINISTIC"
    )
    notes = list(state.get("notes", []))
    notes.append(
        f"finalized with {len(merged)} findings "
        f"({deterministic} deterministic, {len(merged) - deterministic} AI)"
    )
    return {
        "prompt_version": state.get("prompt_version", "review/v1"),
        "notes": notes,
    }


def build_review_graph(
    provider: LLMProvider | None = None, config: ReviewAgentConfig | None = None
):  # -> CompiledStateGraph (unparameterized for 3.12 compat)
    """Assemble and compile the Review Agent (provider optional for tests)."""
    agent_config = config or ReviewAgentConfig(model="test/model")
    builder = StateGraph(ReviewState)
    builder.add_node("load_context", load_context)
    builder.add_node("align_deterministic", align_deterministic)
    builder.add_node("ai_scan", make_ai_scan(provider, agent_config))
    builder.add_node("merge_rank", merge_rank)
    builder.add_node("finalize", finalize)
    builder.add_edge(START, "load_context")
    builder.add_edge("load_context", "align_deterministic")
    builder.add_edge("align_deterministic", "ai_scan")
    builder.add_edge("ai_scan", "merge_rank")
    builder.add_edge("merge_rank", "finalize")
    builder.add_edge("finalize", END)
    return builder.compile()


def align_key(hit: DeterministicFindingIn) -> str:
    """Stable merge key for one deterministic hit."""
    line = "" if hit.line is None else str(hit.line)
    return "|".join([hit.tool, hit.rule_id, hit.file, line])


def snapshot_sizes(files: list[FileSnapshot]) -> int:
    """Count scoped files without retaining contents."""
    return len(files)
