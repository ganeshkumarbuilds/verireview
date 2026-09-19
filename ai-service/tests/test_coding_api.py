"""Coding API tests: graph integration via TestClient, no network."""

from fastapi.testclient import TestClient

from app.main import create_app


def make_client() -> TestClient:
    return TestClient(create_app())


def test_coding_endpoint_returns_diff_when_mocked(monkeypatch):
    # Mock the LLM provider to return a valid diff without calling OpenRouter
    from app.llm import FakeProvider
    import app.api.internal as mod

    payload = '{"diff": "diff --git a/A.java b/A.java\\n--- a/A.java\\n+++ b/A.java\\n@@ -1 +1 @@\\n- x\\n+ y", "explanation": "fix"}'
    fake = FakeProvider(text=payload)

    def fake_provider():
        return fake

    monkeypatch.setattr(mod, "_coding_provider", fake_provider)

    client = make_client()
    resp = client.post(
        "/internal/coding",
        json={
            "fix_request_id": "11111111-1111-1111-1111-111111111111",
            "finding": {
                "id": "f1",
                "title": "SQL concat",
                "description": "desc",
                "file_path": "A.java",
                "line_start": 1,
                "line_end": 1,
                "category": "SECURITY",
                "severity": "HIGH",
                "evidence": "e",
            },
            "scope_note": "use prepared statements",
            "language": "java",
            "files": [{"path": "A.java", "content": "class A {}", "truncated": False}],
        },
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["fix_request_id"] == "11111111-1111-1111-1111-111111111111"
    assert body["diff"].startswith("diff --git")
    assert body["prompt_version"] == "coding/v1"


def test_coding_rejects_malformed_diff(monkeypatch):
    from app.llm import FakeProvider
    import app.api.internal as mod

    fake = FakeProvider(text='{"diff": "bad", "explanation": "x"}')
    monkeypatch.setattr(mod, "_coding_provider", lambda: fake)

    client = make_client()
    resp = client.post(
        "/internal/coding",
        json={
            "fix_request_id": "22222222-2222-2222-2222-222222222222",
            "finding": {
                "id": "f1",
                "title": "Bug",
                "description": "d",
                "file_path": "A.java",
                "line_start": 1,
                "line_end": 1,
                "category": "BUG",
                "severity": "HIGH",
                "evidence": "e",
            },
            "files": [],
        },
    )
    assert resp.status_code == 500
