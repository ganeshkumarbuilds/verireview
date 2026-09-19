"""Config tests: env-driven settings, safe defaults, no secrets in code."""

import pytest

from app.config import Settings, get_settings, reset_settings


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch):
    for key in (
        "HOST",
        "PORT",
        "OPENROUTER_API_KEY",
        "OPENROUTER_BASE_URL",
        "OPENROUTER_MODEL",
        "LLM_TIMEOUT_SECONDS",
        "BACKEND_INTERNAL_URL",
        "AI_SERVICE_INTERNAL_SECRET",
    ):
        monkeypatch.delenv(key, raising=False)
    reset_settings()
    yield
    reset_settings()


def test_defaults_are_safe_and_local():
    settings = Settings()
    assert settings.host == "127.0.0.1"
    assert settings.port == 8001
    assert settings.openrouter_api_key == ""
    assert settings.llm_enabled is False
    assert settings.openrouter_base_url.startswith("https://")


def test_env_overrides(monkeypatch):
    monkeypatch.setenv("OPENROUTER_API_KEY", "test-key")
    monkeypatch.setenv("OPENROUTER_MODEL", "some/model")
    monkeypatch.setenv("PORT", "9001")
    settings = Settings()
    assert settings.llm_enabled is True
    assert settings.openrouter_model == "some/model"
    assert settings.port == 9001


def test_singleton_caches_and_resets():
    first = get_settings()
    assert get_settings() is first
    reset_settings()
    assert get_settings() is not first
