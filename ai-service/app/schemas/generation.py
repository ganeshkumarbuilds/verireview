"""Generation contract schemas (flat wire format, snake_case).

Backend → AI: requirement + explicit stack + non-secret database metadata +
per-request AI credentials. The database password is never sent: generated
code must read secrets from environment variables. AI → backend: a file
plan, then file contents. Every model output validates here first.
"""

from pydantic import BaseModel, Field


class PlanRequest(BaseModel):
    """Backend → AI planning invocation (scoped, no DB/filesystem access)."""

    generation_id: str = Field(min_length=1, max_length=100)
    requirement: str = Field(min_length=1, max_length=20000)
    backend: str = Field(min_length=1, max_length=30)
    frontend: str = Field(min_length=1, max_length=30)
    database: str = Field(min_length=1, max_length=30)
    db_host: str | None = Field(default=None, max_length=500)
    db_port: int | None = Field(default=None, ge=1, le=65535)
    db_name: str | None = Field(default=None, max_length=200)
    db_username: str | None = Field(default=None, max_length=200)
    db_ssl_mode: str | None = Field(default=None, max_length=50)
    ai_provider: str = Field(min_length=1, max_length=30)
    api_key: str = Field(min_length=1, max_length=2000)
    base_url: str | None = Field(default=None, max_length=500)
    model: str = Field(min_length=1, max_length=200)


class PlannedFile(BaseModel):
    path: str = Field(min_length=1, max_length=1000)
    purpose: str = Field(default="", max_length=2000)


class PlanResult(BaseModel):
    """AI → backend file plan.

    Structured sections describe the intended architecture; every field has
    a default so older prompts and stored fixtures keep parsing.
    """

    generation_id: str = Field(min_length=1, max_length=100)
    files: list[PlannedFile] = Field(min_length=1, max_length=80)
    architecture: str = Field(default="", max_length=10000)
    dependencies: list[str] = Field(default_factory=list, max_length=200)
    directories: list[str] = Field(default_factory=list, max_length=200)
    apis: str = Field(default="", max_length=10000)
    steps: list[str] = Field(default_factory=list, max_length=100)
    notes: str = Field(default="", max_length=2000)


class FilesRequest(BaseModel):
    """Backend → AI file-content invocation."""

    generation_id: str = Field(min_length=1, max_length=100)
    requirement: str = Field(min_length=1, max_length=20000)
    backend: str = Field(min_length=1, max_length=30)
    frontend: str = Field(min_length=1, max_length=30)
    database: str = Field(min_length=1, max_length=30)
    db_host: str | None = Field(default=None, max_length=500)
    db_port: int | None = Field(default=None, ge=1, le=65535)
    db_name: str | None = Field(default=None, max_length=200)
    db_username: str | None = Field(default=None, max_length=200)
    db_ssl_mode: str | None = Field(default=None, max_length=50)
    ai_provider: str = Field(min_length=1, max_length=30)
    api_key: str = Field(min_length=1, max_length=2000)
    base_url: str | None = Field(default=None, max_length=500)
    model: str = Field(min_length=1, max_length=200)
    plan: list[PlannedFile] = Field(min_length=1, max_length=80)


class GeneratedFile(BaseModel):
    path: str = Field(min_length=1, max_length=1000)
    content: str = Field(min_length=1, max_length=100_000)
    language: str = Field(default="", max_length=50)


class FilesResult(BaseModel):
    """AI → backend generated files."""

    generation_id: str = Field(min_length=1, max_length=100)
    files: list[GeneratedFile] = Field(min_length=1, max_length=80)
    notes: str = Field(default="", max_length=2000)
