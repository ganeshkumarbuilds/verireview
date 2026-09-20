"""Verified Agent (Phase C) — read-only evaluation of build/test evidence.

The Verified Agent NEVER modifies files. It inspects:
- Build result (exit code, stdout, stderr, status)
- Test result (if applicable)
- Generation context (requirement, stack, database)
- Project structure from plan

And produces a verdict: VERIFIED or REJECTED with evidence-backed reasoning.
"""

from dataclasses import dataclass
from importlib import resources
from typing import Optional

from langgraph.graph import StateGraph

from app.llm import LLMProvider
from app.schemas.verification import (
    GenerationContext,
    VerificationEvidence,
    VerifyRequest,
    VerifyResult,
)

PROMPT_VERSION = "verified/v1"


def load_verified_prompt() -> str:
    return resources.files("app.prompts.verified").joinpath("v1.md").read_text(encoding="utf-8")


@dataclass
class VerifiedAgentConfig:
    model: str
    prompt_version: str = PROMPT_VERSION


def build_verified_graph(provider: LLMProvider, config: VerifiedAgentConfig):
    """Build the Verified Agent graph — single LLM call to evaluate evidence."""
    prompt_template = load_verified_prompt()

    async def evaluate(state: dict) -> dict:
        evidence = state["evidence"]
        context = state["context"]
        prompt_version = state.get("prompt_version", config.prompt_version)

        # Build the evaluation prompt
        eval_prompt = prompt_template.format(
            requirement=context.requirement,
            backend=context.backend,
            frontend=context.frontend,
            database=context.database,
            command=evidence.command,
            exit_code=evidence.exit_code,
            duration_ms=evidence.duration_ms,
            stdout=evidence.stdout or "(empty)",
            stderr=evidence.stderr or "(empty)",
            build_status=evidence.build_status,
            test_status=evidence.test_status,
            failure_reason=evidence.failure_reason or "(none)",
        )

        try:
            response = await provider.complete(
                system="You are a build/test verification agent. Evaluate the evidence and produce a verdict.",
                user=eval_prompt,
                model=config.model,
                temperature=0.1,
            )
            content = response.content.strip()

            # Parse verdict from response (expect JSON or structured text)
            verdict = "REJECTED"
            reason = "Agent evaluation failed"
            tests_total = 0
            tests_passed = 0
            tests_failed = 0
            tests_skipped = 0

            # Try to parse JSON from response
            import json
            try:
                parsed = json.loads(content)
                verdict = parsed.get("verdict", "REJECTED")
                reason = parsed.get("reason", content[:500])
                tests_total = int(parsed.get("tests_total", 0) or 0)
                tests_passed = int(parsed.get("tests_passed", 0) or 0)
                tests_failed = int(parsed.get("tests_failed", 0) or 0)
                tests_skipped = int(parsed.get("tests_skipped", 0) or 0)
            except json.JSONDecodeError:
                # Fallback: check for keywords
                if "VERIFIED" in content.upper() and "REJECTED" not in content.upper():
                    verdict = "VERIFIED"
                    reason = content[:500]

            state["verdict"] = verdict
            state["reason"] = reason
            state["tests_total"] = tests_total
            state["tests_passed"] = tests_passed
            state["tests_failed"] = tests_failed
            state["tests_skipped"] = tests_skipped
            state["log_ref"] = content[:2000]  # Store full response as log

        except Exception as exc:
            state["verdict"] = "REJECTED"
            state["reason"] = f"Verified Agent error: {exc}"
            state["tests_total"] = 0
            state["tests_passed"] = 0
            state["tests_failed"] = 0
            state["tests_skipped"] = 0
            state["log_ref"] = str(exc)

        return state

    graph = StateGraph(dict)
    graph.add_node("evaluate", evaluate)
    graph.set_entry_point("evaluate")
    graph.set_finish_point("evaluate")
    return graph.compile()


async def run_verified_agent(
    provider: LLMProvider,
    config: VerifiedAgentConfig,
    request: VerifyRequest,
) -> VerifyResult:
    """Run the Verified Agent to evaluate evidence."""
    graph = build_verified_graph(provider, config)
    final_state = await graph.ainvoke({
        "evidence": request.evidence.model_dump(),
        "context": request.context.model_dump(),
        "prompt_version": request.prompt_version,
    })

    return VerifyResult(
        verification_run_id=request.evidence.generation_id,
        prompt_version=str(final_state.get("prompt_version", config.prompt_version)),
        verdict=final_state.get("verdict", "REJECTED"),
        reason=final_state.get("reason", "No reason provided"),
        tests_total=final_state.get("tests_total", 0),
        tests_passed=final_state.get("tests_passed", 0),
        tests_failed=final_state.get("tests_failed", 0),
        tests_skipped=final_state.get("tests_skipped", 0),
        log_ref=final_state.get("log_ref"),
        notes=final_state.get("reason", "")[:200],
    )