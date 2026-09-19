"""Coding Agent graph (Phase 9D).

Pipeline: ``load_context → generate_diff → finalize``.
Reuses the same LLM/prompt/config pattern as the Review Agent.
"""

from typing import TypedDict

from langgraph.graph import END, START, StateGraph

from app.agents.coding_agent import CodingAgentConfig, run_coding_agent
from app.llm import LLMProvider
from app.schemas.coding import CodingFinding
from app.schemas.review import FileSnapshot


class CodingState(TypedDict, total=False):
    fix_request_id: str
    finding: dict
    scope_note: str | None
    language: str | None
    files: list[dict]
    prompt_version: str
    notes: list[str]
    diff: str
    explanation: str
    files_changed: int
    additions: int
    deletions: int
    error: str | None


def load_context(state: CodingState) -> dict:
    notes = list(state.get("notes", []))
    notes.append("coding context loaded")
    return {"notes": notes}


def make_generate_diff(provider: LLMProvider | None, config: CodingAgentConfig):
    def generate_diff(state: CodingState) -> dict:
        notes = list(state.get("notes", []))
        if provider is None:
            notes.append("coding skipped (no provider)")
            return {"notes": notes, "error": "no provider"}
        files = [FileSnapshot.model_validate(f) for f in state.get("files", [])]
        finding = CodingFinding.model_validate(state.get("finding"))
        diff, explanation, errors = run_coding_agent(
            files, finding, state.get("scope_note"), state.get("language"), provider, config
        )
        if errors:
            for e in errors:
                notes.append(f"coding parse note: {e}")
            return {"notes": notes, "error": "; ".join(errors)}
        from app.agents.coding_agent import _count_diff_stats

        files_changed, adds, dels = _count_diff_stats(diff)
        notes.append(f"coding generated diff for {finding.file_path}")
        return {
            "diff": diff,
            "explanation": explanation,
            "files_changed": files_changed,
            "additions": adds,
            "deletions": dels,
            "notes": notes,
        }

    return generate_diff


def finalize(state: CodingState) -> dict:
    notes = list(state.get("notes", []))
    if state.get("error"):
        notes.append("coding failed")
    else:
        notes.append("coding finalized")
    return {"prompt_version": state.get("prompt_version", "coding/v1"), "notes": notes}


def build_coding_graph(
    provider: LLMProvider | None = None, config: CodingAgentConfig | None = None
):
    agent_config = config or CodingAgentConfig(model="test/model")
    builder = StateGraph(CodingState)
    builder.add_node("load_context", load_context)
    builder.add_node("generate_diff", make_generate_diff(provider, agent_config))
    builder.add_node("finalize", finalize)
    builder.add_edge(START, "load_context")
    builder.add_edge("load_context", "generate_diff")
    builder.add_edge("generate_diff", "finalize")
    builder.add_edge("finalize", END)
    return builder.compile()
