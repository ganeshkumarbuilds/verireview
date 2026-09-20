"""Verified Agent schemas (Phase C) — read-only evaluation of build/test evidence."""

from pydantic import BaseModel, Field
from typing import Optional


class VerificationEvidence(BaseModel):
    """Build/test execution evidence from sandbox."""
    generation_id: str
    iteration: int
    command: str
    exit_code: int
    duration_ms: int
    stdout: Optional[str] = None
    stderr: Optional[str] = None
    build_status: str
    test_status: str
    failure_reason: Optional[str] = None


class GenerationContext(BaseModel):
    """Minimal generation context for the Verified Agent."""
    generation_id: str
    requirement: str
    backend: str
    frontend: str
    database: str


class VerifyRequest(BaseModel):
    """Request to evaluate build/test evidence and produce a verdict."""
    evidence: VerificationEvidence
    context: GenerationContext
    model: str
    prompt_version: str


class VerifyResult(BaseModel):
    """Result of the Verified Agent evaluation."""
    verification_run_id: str
    agent: str = "verified"
    prompt_version: str
    verdict: str  # VERIFIED | REJECTED
    reason: str
    tests_total: int = 0
    tests_passed: int = 0
    tests_failed: int = 0
    tests_skipped: int = 0
    log_ref: Optional[str] = None
    notes: str = ""