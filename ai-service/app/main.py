"""FastAPI entrypoint (internal API only — never exposed to browsers).

Run: ``uvicorn app.main:app --host 127.0.0.1 --port 8001`` (see README).
"""

from fastapi import FastAPI

from app import __version__
from app.api.internal import router as internal_router
from app.config import get_settings


def create_app() -> FastAPI:
    settings = get_settings()
    app = FastAPI(
        title="VeriReview AI service",
        version=__version__,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.state.settings = settings

    @app.get("/health")
    def health() -> dict:
        return {
            "status": "UP",
            "service": "ai-service",
            "version": __version__,
            "llm_enabled": settings.llm_enabled,
        }

    app.include_router(internal_router)
    return app


app = create_app()
