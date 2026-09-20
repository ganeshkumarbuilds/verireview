"""Generation graphs.

Two small pipelines mirroring the coding graph pattern:
``plan: load_context → generate_plan → finalize`` and
``files: load_context → generate_files → finalize``.
The backend drives them as separate states (PLANNING, GENERATING).
"""

from typing import TypedDict

from langgraph.graph import END, START, StateGraph

from dataclasses import asdict as _asdict

from app.agents.generation_agent import (
    GenerationAgentConfig,
    run_files_agent,
    run_plan_agent,
)
from app.llm import LLMProvider
from app.schemas.generation import FilesRequest, PlanRequest


class PlanState(TypedDict, total=False):
    generation_id: str
    request: dict
    prompt_version: str
    notes: list[str]
    files: list[dict]
    sections: dict
    error: str | None


class FilesState(TypedDict, total=False):
    generation_id: str
    request: dict
    prompt_version: str
    notes: list[str]
    files: list[dict]
    error: str | None


def load_context(state: dict) -> dict:
    notes = list(state.get("notes", []))
    notes.append("generation context loaded")
    return {"notes": notes}


def make_generate_plan(provider: LLMProvider | None, config: GenerationAgentConfig):
    def generate_plan(state: PlanState) -> dict:
        notes = list(state.get("notes", []))
        if provider is None:
            notes.append("generation skipped (no provider)")
            return {"notes": notes, "error": "no provider"}
        request = PlanRequest.model_validate(state.get("request"))
        files, sections, plan_notes, errors = run_plan_agent(request, provider, config)
        if errors:
            for error in errors:
                notes.append(f"plan parse note: {error}")
            return {"notes": notes, "error": "; ".join(errors)}
        notes.append(f"plan generated with {len(files)} files")
        if plan_notes:
            notes.append(plan_notes)
        return {
            "files": [f.model_dump(mode="json") for f in files],
            "sections": _asdict(sections),
            "notes": notes,
        }

    return generate_plan


def make_generate_files(provider: LLMProvider | None, config: GenerationAgentConfig):
    def generate_files(state: FilesState) -> dict:
        notes = list(state.get("notes", []))
        if provider is None:
            notes.append("generation skipped (no provider)")
            return {"notes": notes, "error": "no provider"}
        request = FilesRequest.model_validate(state.get("request"))
        files, files_notes, errors = run_files_agent(request, provider, config)
        if errors:
            for error in errors:
                notes.append(f"files parse note: {error}")
            return {"notes": notes, "error": "; ".join(errors)}
        notes.append(f"files generated: {len(files)}")
        if files_notes:
            notes.append(files_notes)
        return {
            "files": [f.model_dump(mode="json") for f in files],
            "notes": notes,
        }

    return generate_files


def finalize(state: dict) -> dict:
    notes = list(state.get("notes", []))
    if state.get("error"):
        notes.append("generation failed")
    else:
        notes.append("generation finalized")
    return {"prompt_version": state.get("prompt_version", "generation/v1"), "notes": notes}


def build_plan_graph(
    provider: LLMProvider | None = None, config: GenerationAgentConfig | None = None
):
    agent_config = config or GenerationAgentConfig(model="test/model")
    builder = StateGraph(PlanState)
    builder.add_node("load_context", load_context)
    builder.add_node("generate_plan", make_generate_plan(provider, agent_config))
    builder.add_node("finalize", finalize)
    builder.add_edge(START, "load_context")
    builder.add_edge("load_context", "generate_plan")
    builder.add_edge("generate_plan", "finalize")
    builder.add_edge("finalize", END)
    return builder.compile()


def build_files_graph(
    provider: LLMProvider | None = None, config: GenerationAgentConfig | None = None
):
    agent_config = config or GenerationAgentConfig(model="test/model")
    builder = StateGraph(FilesState)
    builder.add_node("load_context", load_context)
    builder.add_node("generate_files", make_generate_files(provider, agent_config))
    builder.add_node("finalize", finalize)
    builder.add_edge(START, "load_context")
    builder.add_edge("load_context", "generate_files")
    builder.add_edge("generate_files", "finalize")
    builder.add_edge("finalize", END)
    return builder.compile()
