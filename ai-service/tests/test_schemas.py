"""Schema tests: enums, caps, and boundary validation of the review contract."""

import pytest
from pydantic import ValidationError

from app.schemas.review import (
    Category,
    DeterministicFindingIn,
    FileSnapshot,
    ProposedFinding,
    ReviewRequest,
    ReviewResult,
    Severity,
)


def test_review_request_minimal_is_valid():
    request = ReviewRequest(review_id="r1", project_id="p1")
    assert request.files == []
    assert request.deterministic_findings == []


def test_review_request_rejects_empty_ids():
    with pytest.raises(ValidationError):
        ReviewRequest(review_id="", project_id="p1")


def test_file_snapshot_caps_content():
    with pytest.raises(ValidationError):
        FileSnapshot(path="a.java", content="x" * 200_001)


def test_proposed_finding_confidence_bounds():
    ok = ProposedFinding(
        category=Category.BUG,
        severity=Severity.HIGH,
        title="Null dereference",
        confidence=0.9,
    )
    assert ok.confidence == 0.9
    with pytest.raises(ValidationError):
        ProposedFinding(
            category=Category.BUG,
            severity=Severity.HIGH,
            title="x",
            confidence=1.5,
        )


def test_deterministic_finding_line_must_be_non_negative():
    with pytest.raises(ValidationError):
        DeterministicFindingIn(tool="checkstyle", rule_id="R", file="f", line=-1)


def test_review_result_caps_findings_at_100():
    with pytest.raises(ValidationError):
        ReviewResult(
            review_id="r1",
            prompt_version="review/v1",
            findings=[
                ProposedFinding(
                    category=Category.STYLE, severity=Severity.INFO, title=f"t{i}"
                )
                for i in range(101)
            ],
        )
