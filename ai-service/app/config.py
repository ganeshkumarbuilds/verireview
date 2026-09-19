"""Environment-driven configuration (12-factor).

Every setting comes from the process environment with safe local defaults;
no secrets live in code (SECURITY_DESIGN T9). Production values arrive via
`.env` / Docker / CI secrets — see the root `.env.example` keys
``OPENROUTER_API_KEY``, ``AI_SERVICE_INTERNAL_SECRET``, ``BACKEND_INTERNAL_URL``.
"""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """AI-service settings. Instantiation never performs I/O or calls."""

    model_config = SettingsConfigDict(env_prefix="", case_sensitive=False)

    # Service binding.
    host: str = "127.0.0.1"
    port: int = 8001

    # OpenRouter-compatible LLM access. Empty key = LLM calls disabled;
    # Phase 7A never calls — the provider layer only shapes requests.
    openrouter_api_key: str = ""
    openrouter_base_url: str = "https://openrouter.ai/api/v1"
    openrouter_model: str = "openrouter/auto"
    llm_timeout_seconds: float = 60.0

    # Backend callback / shared-secret auth for /internal/* (Phase 7+).
    backend_internal_url: str = "http://localhost:8080"
    ai_service_internal_secret: str = ""

    @property
    def llm_enabled(self) -> bool:
        """True only when a key is configured. Phase 7A paths ignore this."""
        return bool(self.openrouter_api_key.strip())


_settings: "Settings | None" = None


def get_settings() -> Settings:
    """Process-wide settings singleton (re-read via ``reset_settings``)."""
    global _settings
    if _settings is None:
        _settings = Settings()
    return _settings


def reset_settings() -> None:
    """Drop the cached singleton (tests only)."""
    global _settings
    _settings = None
