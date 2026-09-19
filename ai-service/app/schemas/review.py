"""Review-agent contract schemas (AGENT_DESIGN §3).

Mirrors the backend's deterministic finding shape plus the AI layer. Every
model crossing the backend boundary validates here first; unparseable output
is a failed execution, never silently accepted.
"""

from enum import Enum

from pydantic import BaseModel, Field


class Category(str, Enum):
    BUG = "BUG"
    SECURITY = "SECURITY"
    CODE_QUALITY = "CODE_QUALITY"
    PERFORMANCE = "PERFORMANCE"
    ARCHITECTURE = "ARCHITECTURE"
    DEPENDENCY = "DEPENDENCY"
    MISSING_TEST = "MISSING_TEST"
    STYLE = "STYLE"


class Severity(str, Enum):
    CRITICAL = "CRITICAL"
    HIGH = "HIGH"
    MEDIUM = "MEDIUM"
    LOW = "LOW"
    INFO = "INFO"


class FindingSource(str, Enum):
    DETERMINISTIC = "DETERMINISTIC"
    AI = "AI"
    VERIFIED = "VERIFIED"


class FileSnapshot(BaseModel):
    """One scoped source file. Content is truncated upstream with an
    omission marker; paths are backend-normalized (no traversal)."""

    path: str = Field(min_length=1, max_length=1000)
    language: str | None = Field(default=None, max_length=50)
    content: str = Field(max_length=200_000)
    truncated: bool = False


class DeterministicFindingIn(BaseModel):
    """One deterministic tool hit, copied verbatim from the backend."""

    tool: str = Field(min_length=1, max_length=50)
    rule_id: str = Field(min_length=1, max_length=200)
    file: str = Field(min_length=1, max_length=1000)
    line: int | None = Field(default=None, ge=0)
    message: str = Field(max_length=2000)


class ReviewRequest(BaseModel):
    """Backend → AI review invocation (scoped, least-context)."""

    review_id: str = Field(min_length=1, max_length=100)
    project_id: str = Field(min_length=1, max_length=100)
    language: str | None = Field(default=None, max_length=50)
    files: list[FileSnapshot] = Field(default_factory=list, max_length=200)
    deterministic_findings: list[DeterministicFindingIn] = Field(
        default_factory=list, max_length=2000
    )
    idempotency_key: str | None = Field(default=None, max_length=100)


class ProposedFinding(BaseModel):
    """One AI-proposed finding. `source` is AI for newly raised items;
    deterministic hits are echoed with enrichment, never upgraded."""

    category: Category
    severity: Severity
    title: str = Field(min_length=1, max_length=500)
    description: str = Field(default="", max_length=2000)
    file_path: str | None = Field(default=None, max_length=1000)
    line_start: int | None = Field(default=None, ge=0)
    line_end: int | None = Field(default=None, ge=0)
    evidence: str = Field(default="", max_length=2000)
    source: FindingSource = FindingSource.AI
    suggested_fix_hint: str = Field(default="", max_length=500)
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)


class ReviewResult(BaseModel):
    """AI → backend review proposal. The backend re-validates, dedupes,
    persists, and owns the verdict lifecycle."""

    review_id: str = Field(min_length=1, max_length=100)
    agent: str = Field(default="review", max_length=50)
    prompt_version: str = Field(min_length=1, max_length=50)
    findings: list[ProposedFinding] = Field(default_factory=list, max_length=100)
    notes: str = Field(default="", max_length=2000)
