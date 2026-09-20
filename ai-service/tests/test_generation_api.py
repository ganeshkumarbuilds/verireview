"""Generation endpoint tests (fake provider monkeypatched in — no network)."""

import json

from fastapi.testclient import TestClient

import app.api.internal as internal
from app.llm import FakeProvider
from app.main import create_app

client = TestClient(create_app())


def _plan_body():
    return {
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


def test_plan_endpoint_returns_files(monkeypatch):
    payload = {"files": [{"path": "app.py", "purpose": "API"}], "notes": ""}
    monkeypatch.setattr(
        internal, "_generation_provider", lambda api_key, base_url: FakeProvider(text=json.dumps(payload))
    )
    response = client.post("/internal/generate/plan", json=_plan_body())
    assert response.status_code == 200
    body = response.json()
    assert body["generation_id"] == "g1"
    assert body["files"] == [{"path": "app.py", "purpose": "API"}]
    assert "api_key" not in json.dumps(body)
    assert "test-key" not in json.dumps(body)


def test_plan_endpoint_returns_structured_sections(monkeypatch):
    payload = {
        "architecture": "FastAPI + single module",
        "dependencies": ["fastapi==0.110"],
        "directories": ["src"],
        "apis": "GET /todos",
        "steps": ["1. Scaffold"],
        "files": [{"path": "app.py", "purpose": "API"}],
        "notes": "",
    }
    monkeypatch.setattr(
        internal, "_generation_provider", lambda api_key, base_url: FakeProvider(text=json.dumps(payload))
    )
    response = client.post("/internal/generate/plan", json=_plan_body())
    assert response.status_code == 200
    body = response.json()
    assert body["architecture"] == "FastAPI + single module"
    assert body["dependencies"] == ["fastapi==0.110"]
    assert body["directories"] == ["src"]
    assert body["apis"] == "GET /todos"
    assert body["steps"] == ["1. Scaffold"]
    assert "api_key" not in json.dumps(body)
    assert "test-key" not in json.dumps(body)


def test_files_endpoint_returns_contents(monkeypatch):
    payload = {"files": [{"path": "app.py", "content": "x = 1", "language": "python"}]}
    monkeypatch.setattr(
        internal, "_generation_provider", lambda api_key, base_url: FakeProvider(text=json.dumps(payload))
    )
    body = _plan_body()
    body["plan"] = [{"path": "app.py", "purpose": "API"}]
    response = client.post("/internal/generate/files", json=body)
    assert response.status_code == 200
    assert response.json()["files"][0]["content"] == "x = 1"


def test_files_endpoint_rejects_unplanned_paths(monkeypatch):
    payload = {"files": [{"path": "rogue.py", "content": "y"}]}
    monkeypatch.setattr(
        internal, "_generation_provider", lambda api_key, base_url: FakeProvider(text=json.dumps(payload))
    )
    body = _plan_body()
    body["plan"] = [{"path": "app.py", "purpose": "API"}]
    response = client.post("/internal/generate/files", json=body)
    assert response.status_code == 500


def test_plan_endpoint_requires_key():
    body = _plan_body()
    body["api_key"] = "  "
    # Pydantic accepts blank here; the provider factory must refuse it.
    response = client.post("/internal/generate/plan", json=body)
    assert response.status_code == 500
