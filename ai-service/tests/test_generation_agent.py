"""Generation Agent reasoning tests (FakeProvider only — no network)."""

import json

from app.agents.generation_agent import (
    GenerationAgentConfig,
    build_files_prompt,
    build_plan_prompt,
    parse_files_output,
    parse_plan_output,
    run_files_agent,
    run_plan_agent,
    validate_generated_path,
)
from app.llm import FakeProvider
from app.schemas.generation import FilesRequest, PlannedFile, PlanRequest

CONFIG = GenerationAgentConfig(model="test/model")


def _plan_request(**overrides):
    base = {
        "generation_id": "g1",
        "requirement": "A todo API",
        "backend": "PYTHON_FASTAPI",
        "frontend": "NONE",
        "database": "POSTGRESQL",
        "db_host": "localhost",
        "db_port": 5432,
        "db_name": "todos",
        "db_username": "app",
        "db_ssl_mode": None,
        "ai_provider": "OPENROUTER",
        "api_key": "test-key",
        "base_url": None,
        "model": "test/model",
    }
    base.update(overrides)
    return PlanRequest(**base)


def test_plan_prompt_includes_requirement_and_stack():
    system, user = build_plan_prompt(_plan_request())
    assert "A todo API" in user
    assert "PYTHON_FASTAPI" in user
    assert "POSTGRESQL" in user
    assert "test-key" not in user
    assert system.strip()


def test_plan_prompt_never_contains_secrets():
    system, user = build_plan_prompt(_plan_request())
    assert "test-key" not in system
    assert "test-key" not in user
    assert "api_key" not in user


def test_files_prompt_lists_plan_and_hides_key():
    request = FilesRequest(
        **{**_plan_request().model_dump(), "plan": [{"path": "app.py", "purpose": "API"}]}
    )
    system, user = build_files_prompt(request)
    assert "app.py" in user
    assert "test-key" not in user
    assert system.strip()


def test_validate_generated_path():
    assert validate_generated_path("src/App.java") == []
    assert validate_generated_path("") != []
    assert validate_generated_path("../evil.sh") != []
    assert validate_generated_path("/etc/passwd") != []
    assert validate_generated_path("C:/Windows/x") != []
    assert validate_generated_path("a\\b") != []


def test_parse_plan_output_valid():
    payload = {"files": [{"path": "app.py", "purpose": "API"}], "notes": "ok"}
    files, sections, notes, errors = parse_plan_output(json.dumps(payload))
    assert errors == []
    assert [f.path for f in files] == ["app.py"]
    assert notes == "ok"
    assert sections.architecture == ""
    assert sections.dependencies == ()


def test_parse_plan_output_rejects_bad_paths_and_duplicates():
    _, _, _, errors = parse_plan_output(
        json.dumps({"files": [{"path": "../evil.sh", "purpose": "x"}]})
    )
    assert errors

    _, _, _, errors = parse_plan_output(
        json.dumps(
            {"files": [{"path": "a.py", "purpose": "x"}, {"path": "a.py", "purpose": "y"}]}
        )
    )
    assert any("duplicate" in e for e in errors)

    _, _, _, errors = parse_plan_output("not json {{{")
    assert errors


def test_parse_plan_output_structured_sections():
    payload = {
        "architecture": "FastAPI + single module",
        "dependencies": ["fastapi==0.110"],
        "directories": ["src/", "tests"],
        "apis": "GET /todos",
        "steps": ["1. Scaffold", "2. Implement"],
        "files": [{"path": "app.py", "purpose": "API"}],
        "notes": "",
    }
    files, sections, _, errors = parse_plan_output(json.dumps(payload))
    assert errors == []
    assert sections.architecture == "FastAPI + single module"
    assert sections.dependencies == ("fastapi==0.110",)
    assert sections.directories == ("src", "tests")
    assert sections.apis == "GET /todos"
    assert sections.steps == ("1. Scaffold", "2. Implement")


def test_parse_plan_output_rejects_bad_sections():
    base = {"files": [{"path": "app.py", "purpose": "API"}]}

    _, _, _, errors = parse_plan_output(json.dumps({**base, "architecture": 42}))
    assert any("architecture" in e for e in errors)

    _, _, _, errors = parse_plan_output(json.dumps({**base, "dependencies": "fastapi"}))
    assert any("dependencies" in e for e in errors)

    _, _, _, errors = parse_plan_output(json.dumps({**base, "directories": ["../evil"]}))
    assert any("project-relative" in e for e in errors)

    _, _, _, errors = parse_plan_output(json.dumps({**base, "steps": ["ok", 42]}))
    assert any("steps" in e for e in errors)


def test_parse_files_output_requires_exact_plan_match():
    plan = [PlannedFile(path="app.py", purpose="API")]
    payload = {
        "files": [{"path": "app.py", "content": "x = 1", "language": "python"}],
        "notes": "",
    }
    files, _, errors = parse_files_output(json.dumps(payload), ["app.py"])
    assert errors == []
    assert files[0].content == "x = 1"
    assert all(isinstance(p, str) for p in [plan[0].path])

    _, _, errors = parse_files_output(json.dumps(payload), ["app.py", "missing.py"])
    assert any("missing" in e for e in errors)

    extra = {
        "files": [
            {"path": "app.py", "content": "x"},
            {"path": "rogue.py", "content": "y"},
        ]
    }
    _, _, errors = parse_files_output(json.dumps(extra), ["app.py"])
    assert any("unplanned" in e for e in errors)


def test_run_plan_and_files_agents():
    plan_payload = {"files": [{"path": "app.py", "purpose": "API"}], "notes": ""}
    files, sections, notes, errors = run_plan_agent(
        _plan_request(), FakeProvider(text=json.dumps(plan_payload)), CONFIG
    )
    assert errors == []
    assert [f.path for f in files] == ["app.py"]
    assert sections.directories == ()
    assert notes == ""

    files_payload = {
        "files": [{"path": "app.py", "content": "x = 1", "language": "python"}]
    }
    request = FilesRequest(
        **{**_plan_request().model_dump(), "plan": [{"path": "app.py", "purpose": "API"}]}
    )
    generated, _, errors = run_files_agent(
        request, FakeProvider(text=json.dumps(files_payload)), CONFIG
    )
    assert errors == []
    assert generated[0].path == "app.py"
