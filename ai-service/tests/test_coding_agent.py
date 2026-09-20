"""Coding Agent reasoning tests (FakeProvider only — no network)."""

import json

import pytest

from app.agents.coding_agent import (
    build_coding_prompt,
    load_coding_prompt_template,
    parse_coding_output,
    run_coding_agent,
    validate_diff,
    CodingAgentConfig,
)
from app.llm import FakeProvider
from app.schemas.coding import CodingFinding
from app.schemas.review import FileSnapshot

CONFIG = CodingAgentConfig(model="test/model")

def _finding(**overrides):
    base = {
        "id": "f1",
        "title": "SQL concat",
        "description": "User input reaches query",
        "file_path": "Dao.java",
        "line_start": 41,
        "line_end": 41,
        "category": "SECURITY",
        "severity": "HIGH",
        "evidence": "concat",
    }
    base.update(overrides)
    return CodingFinding(**base)


def test_prompt_template_is_versioned():
    template = load_coding_prompt_template()
    assert "diff --git" in template
    assert "coding/v1" in template or "Coding Agent" in template


def test_prompt_includes_finding_and_scope():
    files = [FileSnapshot(path="Dao.java", content="class Dao {}")]
    system, user = build_coding_prompt(files, _finding(), "use prepared statements", "java")
    assert "Dao.java" in user
    assert "SQL concat" in user
    assert "use prepared statements" in user
    assert system.strip()


def test_validate_diff_accepts_valid():
    diff = """diff --git a/src/Main.java b/src/Main.java
--- a/src/Main.java
+++ b/src/Main.java
@@ -10,3 +10,4 @@
- old
+ new"""
    assert validate_diff(diff) == []


def test_validate_diff_rejects_invalid():
    assert "empty" in " ".join(validate_diff(""))
    assert any("unified format" in e for e in validate_diff("not a diff"))
    bad = """diff --git a/src/Main.java b/src/Main.java
--- a/src/Main.java
+++ b/src/Main.java
@@ -1,3 +1,3 @@
+// verified fix"""
    assert any("verification" in e.lower() for e in validate_diff(bad))


def test_validate_diff_rejects_traversal_absolute_and_binary():
    def with_paths(path):
        return (
            f"diff --git a/{path} b/{path}\n"
            f"--- a/{path}\n"
            f"+++ b/{path}\n"
            "@@ -1 +1 @@\n"
            "+x"
        )

    assert any("illegal path" in e for e in validate_diff(with_paths("../evil.sh")))
    assert any("illegal path" in e for e in validate_diff(with_paths("src/../../etc/x")))
    assert any("illegal path" in e for e in validate_diff(with_paths("/etc/passwd")))
    assert any("illegal path" in e for e in validate_diff(with_paths("C:/Windows/evil")))

    binary = "diff --git a/img.png b/img.png\nBinary files a/img.png and b/img.png differ"
    assert any("binary" in e.lower() for e in validate_diff(binary))


def test_validate_diff_rejects_excessive_changes():
    many_files = "".join(
        f"diff --git a/F{i}.java b/F{i}.java\n"
        f"--- a/F{i}.java\n"
        f"+++ b/F{i}.java\n"
        "@@ -1 +1 @@\n-x\n+y\n"
        for i in range(6)
    )
    assert any("limit is 5" in e for e in validate_diff(many_files))

    many_lines = "diff --git a/Big.java b/Big.java\n--- a/Big.java\n+++ b/Big.java\n@@ -1 +1 @@\n"
    many_lines += "".join(f"+line {i}\n" for i in range(201))
    assert any("changed lines" in e for e in validate_diff(many_lines))


def test_parse_valid_output():
    payload = {"diff": "diff --git a/A.java b/A.java\n--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n- x\n+ y", "explanation": "fix"}
    diff, exp, errors = parse_coding_output(json.dumps(payload))
    assert errors == []
    assert diff.startswith("diff --git")
    assert exp == "fix"

    # fences
    diff2, _, _ = parse_coding_output("```json\n" + json.dumps(payload) + "\n```")
    assert diff2 == diff


def test_parse_malformed_never_raises():
    diff, _, errors = parse_coding_output("not json {{{")
    assert diff == "" and len(errors) == 1

    diff, _, errors = parse_coding_output(json.dumps({"explanation": "no diff"}))
    assert diff == "" and len(errors) == 1

    diff, _, errors = parse_coding_output(json.dumps({"diff": "bad diff"}))
    assert diff == "" and len(errors) == 1


def test_run_agent_normalizes_and_bounds():
    payload = {"diff": "diff --git a/A.java b/A.java\n--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n- x\n+ y", "explanation": "fix"}
    provider = FakeProvider(text=json.dumps(payload))
    diff, exp, errors = run_coding_agent(
        [FileSnapshot(path="A.java", content="x")], _finding(), "note", "java", provider, CONFIG
    )
    assert errors == []
    assert diff.startswith("diff --git")
    assert provider.calls[0].model == "test/model"
