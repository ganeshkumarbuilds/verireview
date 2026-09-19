"""Review Agent reasoning tests (FakeProvider only — no network, ever)."""

import json

import pytest

from app.agents.review_agent import (
    ReviewAgentConfig,
    build_review_prompt,
    echo_deterministic,
    finding_key,
    load_prompt_template,
    merge_rank,
    normalize_category,
    normalize_path,
    normalize_severity,
    parse_review_output,
    run_review_agent,
)
from app.llm import FakeProvider, LLMError, LLMRequest
from app.schemas.review import (
    Category,
    DeterministicFindingIn,
    FileSnapshot,
    FindingSource,
    ProposedFinding,
    Severity,
)

CONFIG = ReviewAgentConfig(model="test/model")


def _hit(**overrides):
    base = {
        "tool": "checkstyle",
        "rule_id": "LineLength",
        "file": "Main.java",
        "line": 3,
        "message": "too long",
    }
    base.update(overrides)
    return DeterministicFindingIn(**base)


def test_prompt_template_is_versioned():
    template = load_prompt_template()
    assert "VERIFIED" in template
    assert "findings" in template


def test_prompt_marks_truncation_and_scope():
    files = [
        FileSnapshot(path="A.java", content="class A {}"),
        FileSnapshot(path="B.java", content="x" * 10, truncated=True),
    ]
    system, user = build_review_prompt(files, [_hit()], "java")
    assert "language: java" in user
    assert "--- file: A.java ---" in user
    assert "truncated by the backend" in user
    assert "[checkstyle] LineLength Main.java:3: too long" in user
    assert system.strip()


def test_parse_valid_object_and_bare_array():
    text = json.dumps(
        {
            "findings": [
                {
                    "category": "security",
                    "severity": "HIGH",
                    "title": "SQL concat",
                    "description": "d",
                    "file_path": "Main.java",
                    "line_start": 3,
                    "line_end": 3,
                    "evidence": "rule",
                    "source": "AI",
                    "suggested_fix_hint": "params",
                    "confidence": 0.9,
                }
            ]
        }
    )
    findings, errors = parse_review_output(text)
    assert errors == []
    assert len(findings) == 1
    assert findings[0].severity == Severity.HIGH

    findings2, _ = parse_review_output("```json\n" + text + "\n```")
    assert len(findings2) == 1


def test_parse_malformed_never_raises():
    findings, errors = parse_review_output("this is not json {{{")
    assert findings == [] and len(errors) == 1

    findings, errors = parse_review_output('{"findings": "nope"}')
    assert findings == [] and len(errors) == 1

    findings, errors = parse_review_output(
        json.dumps({"findings": [{"title": ""}, "junk", {"title": "ok",
            "category": "BUG", "severity": "LOW"}]})
    )
    assert [f.title for f in findings] == ["ok"]
    assert len(errors) == 2


def test_severity_and_category_aliases_with_safe_fallbacks():
    assert normalize_severity("blocker") == Severity.CRITICAL
    assert normalize_severity("Warning") == Severity.MEDIUM
    assert normalize_severity("nonsense") == Severity.MEDIUM
    assert normalize_severity(None) == Severity.MEDIUM
    assert normalize_category("vulnerability") == Category.SECURITY
    assert normalize_category("perf") == Category.PERFORMANCE
    assert normalize_category("nonsense") == Category.CODE_QUALITY
    assert normalize_category(None) == Category.CODE_QUALITY


def test_path_normalization_confines_to_scope():
    assert normalize_path("src/../Main.java") == "src/Main.java"
    assert normalize_path("/abs/path.java") == "abs/path.java"
    assert normalize_path("C:\\proj\\A.java") == "proj/A.java"
    assert normalize_path("..") is None
    assert normalize_path("") is None
    assert normalize_path(None) is None


def test_run_agent_normalizes_and_bounds_output():
    payload = {
        "findings": [
            {
                "category": "security",
                "severity": "HIGH",
                "title": "SQL concat",
                "description": "user input reaches the query",
                "file_path": "src/../Dao.java",
                "line_start": 41,
                "line_end": 41,
                "evidence": "concat",
                "source": "AI",
                "suggested_fix_hint": "use prepared statements",
                "confidence": 0.9,
            },
            {
                "category": "mystery",
                "severity": "mystery",
                "title": "Odd smell",
                "description": "d",
                "file_path": "../evil.java",
                "line_start": -5,
                "line_end": 2,
                "evidence": "e",
                "source": "VERIFIED",
                "suggested_fix_hint": "",
                "confidence": 99,
            },
        ]
    }
    provider = FakeProvider(text=json.dumps(payload))
    findings, errors = run_review_agent(
        [FileSnapshot(path="Dao.java", content="x")], [], "java", provider, CONFIG
    )
    assert errors == []
    assert len(findings) == 2
    first, second = findings
    assert (first.category, first.severity) == (Category.SECURITY, Severity.HIGH)
    assert first.file_path == "src/Dao.java"
    # Unknown enums fall back; VERIFIED is never accepted from the model.
    assert (second.category, second.severity) == (Category.CODE_QUALITY, Severity.MEDIUM)
    assert second.source == FindingSource.AI
    assert second.file_path == "evil.java"
    assert second.line_start is None
    assert second.confidence == 1.0
    # Provider received the pinned model config.
    assert provider.calls[0].model == "test/model"
    assert provider.calls[0].temperature == pytest.approx(0.2)


def test_run_agent_propagates_transport_errors():
    class Broken(FakeProvider):
        def complete(self, request: LLMRequest):
            from app.llm import LLMError as E

            raise E("down")

    with pytest.raises(LLMError):
        run_review_agent([], [], None, Broken(), CONFIG)


def test_echo_keeps_deterministic_distinguishable():
    echoed = echo_deterministic([_hit()])
    assert len(echoed) == 1
    assert echoed[0].source == FindingSource.DETERMINISTIC
    assert echoed[0].title == "LineLength"
    assert "tool:checkstyle" in (echoed[0].evidence or "")


def test_merge_rank_deterministic_first_and_deduped():
    det = echo_deterministic([_hit()])
    ai_dupe_same_location = ProposedFinding(
        category=Category.SECURITY,
        severity=Severity.CRITICAL,
        title="Also long line",
        description="d",
        file_path="Main.java",
        line_start=3,
        line_end=3,
        evidence="e",
        source=FindingSource.AI,
        suggested_fix_hint="",
        confidence=1.0,
    )
    ai_low = ProposedFinding(
        category=Category.STYLE,
        severity=Severity.LOW,
        title="Nit",
        description="d",
        file_path="Other.java",
        line_start=1,
        line_end=1,
        evidence="e",
        source=FindingSource.AI,
        suggested_fix_hint="",
        confidence=0.5,
    )
    ai_high = ProposedFinding(
        category=Category.BUG,
        severity=Severity.HIGH,
        title="Real bug",
        description="d",
        file_path="Other.java",
        line_start=9,
        line_end=9,
        evidence="e",
        source=FindingSource.AI,
        suggested_fix_hint="",
        confidence=0.9,
    )
    merged = merge_rank(det, [ai_dupe_same_location, ai_low, ai_high, ai_high])
    # AI restatement of the deterministic location is dropped; exact AI
    # duplicates collapse; deterministic leads, then severity order.
    assert [f.title for f in merged] == ["LineLength", "Real bug", "Nit"]
    assert merged[0].source == FindingSource.DETERMINISTIC


def test_merge_rank_caps_output():
    many = [
        ProposedFinding(
            category=Category.STYLE,
            severity=Severity.INFO,
            title=f"Nit {i}",
            description="d",
            file_path=f"F{i}.java",
            line_start=i,
            line_end=i,
            evidence="e",
            source=FindingSource.AI,
            suggested_fix_hint="",
            confidence=0.1,
        )
        for i in range(150)
    ]
    assert len(merge_rank([], many, max_findings=100)) == 100


def test_finding_key_stable():
    first = echo_deterministic([_hit()])[0]
    assert finding_key(first) == finding_key(echo_deterministic([_hit()])[0])
