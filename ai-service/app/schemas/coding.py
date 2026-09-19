"""Coding-agent contract schemas (Phase 9D).

Backend → AI: scoped finding + fix scope + file snapshots (least-context).
AI → backend: strict unified-diff proposal. Every model output validates
here first; malformed output is a failed execution, never silently accepted.
"""

from pydantic import BaseModel, Field

from app.schemas.review import FileSnapshot


class CodingFinding(BaseModel):
    """Finding to be fixed (minimal context for the model)."""

    id: str = Field(min_length=1, max_length=100)
    title: str = Field(min_length=1, max_length=500)
    description: str | None = Field(default=None, max_length=5000)
    file_path: str | None = Field(default=None, max_length=1000)
    line_start: int | None = Field(default=None, ge=0)
    line_end: int | None = Field(default=None, ge=0)
    category: str = Field(default="CODE_QUALITY", max_length=50)
    severity: str = Field(default="MEDIUM", max_length=50)
    evidence: str | None = Field(default=None, max_length=5000)


class CodingRequest(BaseModel):
    """Backend → AI coding invocation (scoped, no DB/filesystem access)."""

    fix_request_id: str = Field(min_length=1, max_length=100)
    finding: CodingFinding
    scope_note: str | None = Field(default=None, max_length=5000)
    language: str | None = Field(default=None, max_length=50)
    files: list[FileSnapshot] = Field(default_factory=list, max_length=200)
    idempotency_key: str | None = Field(default=None, max_length=100)


class CodingResult(BaseModel):
    """AI → backend coding proposal (strict, validated before persistence)."""

    fix_request_id: str = Field(min_length=1, max_length=100)
    agent: str = Field(default="coding", max_length=50)
    prompt_version: str = Field(min_length=1, max_length=50)
    diff: str = Field(min_length=1, max_length=200_000)
    files_changed: int = Field(ge=0)
    additions: int = Field(ge=0)
    deletions: int = Field(ge=0)
    explanation: str = Field(default="", max_length=5000)
    notes: str = Field(default="", max_length=2000)
