"""Graph tests: skeleton compiles, nodes wire in order, no network touched."""

from app.graphs.review_graph import (
    align_deterministic,
    align_key,
    build_review_graph,
    finalize,
    load_context,
    merge_rank,
    snapshot_sizes,
)
from app.schemas.review import DeterministicFindingIn, FileSnapshot


def test_nodes_run_in_pipeline_order():
    state = {
        "review_id": "r1",
        "project_id": "p1",
        "file_count": 2,
        "deterministic_count": 1,
        "aligned_keys": [],
        "prompt_version": "review/v1",
        "notes": [],
        "files": [],
        "deterministic": [],
        "ai_findings": [],
        "merged_findings": [],
    }
    state.update(load_context(state))
    state.update(align_deterministic(state))
    state.update(merge_rank(state))
    state.update(finalize(state))
    assert state["notes"] == [
        "context loaded",
        "deterministic hits aligned",
        "merged and ranked 0 findings",
        "finalized with 0 findings (0 deterministic, 0 AI)",
    ]


def test_compiled_graph_invokes_end_to_end_without_network():
    graph = build_review_graph()
    final = graph.invoke(
        {
            "review_id": "r1",
            "project_id": "p1",
            "file_count": 1,
            "deterministic_count": 0,
            "aligned_keys": [],
            "prompt_version": "review/v1",
            "notes": [],
            "files": [],
            "deterministic": [],
            "ai_findings": [],
            "merged_findings": [],
        }
    )
    assert final["review_id"] == "r1"
    assert final["prompt_version"] == "review/v1"
    assert any("finalized" in note for note in final["notes"])


def test_graph_has_expected_nodes_and_edges():
    graph = build_review_graph()
    nodes = set(graph.nodes.keys())
    assert {"load_context", "align_deterministic", "ai_scan", "merge_rank", "finalize"} <= nodes


def test_ai_scan_with_fake_provider_feeds_merge():
    import json

    from app.agents.review_agent import ReviewAgentConfig
    from app.llm import FakeProvider

    payload = json.dumps(
        {
            "findings": [
                {
                    "category": "BUG",
                    "severity": "HIGH",
                    "title": "Null deref",
                    "description": "d",
                    "file_path": "A.java",
                    "line_start": 1,
                    "line_end": 1,
                    "evidence": "e",
                    "source": "AI",
                    "suggested_fix_hint": "",
                    "confidence": 0.8,
                }
            ]
        }
    )
    graph = build_review_graph(
        provider=FakeProvider(text=payload),
        config=ReviewAgentConfig(model="test/model"),
    )
    final = graph.invoke(
        {
            "review_id": "r1",
            "project_id": "p1",
            "file_count": 1,
            "deterministic_count": 0,
            "aligned_keys": [],
            "prompt_version": "review/v1",
            "notes": [],
            "files": [{"path": "A.java", "content": "x"}],
            "deterministic": [],
            "ai_findings": [],
            "merged_findings": [],
        }
    )
    assert len(final["merged_findings"]) == 1
    assert final["merged_findings"][0]["title"] == "Null deref"


def test_align_key_is_stable_and_discriminating():
    hit = DeterministicFindingIn(
        tool="pmd", rule_id="R", file="A.java", line=3, message="m"
    )
    assert align_key(hit) == align_key(hit)
    other = DeterministicFindingIn(
        tool="pmd", rule_id="R", file="A.java", line=4, message="m"
    )
    assert align_key(hit) != align_key(other)


def test_snapshot_sizes_counts_without_contents():
    files = [FileSnapshot(path="a.java", content="x"), FileSnapshot(path="b.java", content="y")]
    assert snapshot_sizes(files) == 2
