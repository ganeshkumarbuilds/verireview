"""API tests: health endpoint and the stubbed internal review endpoint."""

from fastapi.testclient import TestClient

from app.main import create_app


def make_client() -> TestClient:
    return TestClient(create_app())


def test_health_reports_up_without_llm():
    response = make_client().get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UP"
    assert body["service"] == "ai-service"
    assert body["version"]
    assert body["llm_enabled"] is False


def test_internal_review_accepts_scoped_request():
    response = make_client().post(
        "/internal/review",
        json={
            "review_id": "r1",
            "project_id": "p1",
            "language": "java",
            "files": [{"path": "Main.java", "content": "class Main {}"}],
            "deterministic_findings": [
                {
                    "tool": "checkstyle",
                    "rule_id": "LineLength",
                    "file": "Main.java",
                    "line": 3,
                    "message": "too long",
                }
            ],
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert body["review_id"] == "r1"
    assert body["agent"] == "review"
    assert body["prompt_version"] == "review/v1"
    # No API key in tests: deterministic echo only, still distinguishable.
    assert len(body["findings"]) == 1
    assert body["findings"][0]["source"] == "DETERMINISTIC"
    assert body["findings"][0]["title"] == "LineLength"


def test_internal_review_rejects_empty_ids():
    response = make_client().post(
        "/internal/review", json={"review_id": "", "project_id": "p1"}
    )
    assert response.status_code == 422
