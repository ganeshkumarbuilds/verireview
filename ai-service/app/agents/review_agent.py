"""Review Agent reasoning (AGENT_DESIGN §3, Phase 7B).

Pure, provider-agnostic logic: prompt building, strict output parsing,
normalization, and merge/dedupe/rank. The only I/O is one
``LLMProvider.complete`` call in :func:`run_review_agent`; tests inject
``FakeProvider`` — no network, ever.
"""

from dataclasses import dataclass
from importlib import resources
import re

from app.llm import LLMProvider, LLMRequest
from app.schemas.review import (
    Category,
    DeterministicFindingIn,
    FileSnapshot,
    FindingSource,
    ProposedFinding,
    Severity,
)

PROMPT_VERSION = "review/v1"
MAX_FINDINGS = 100

_SEVERITY_ALIASES = {
    "critical": Severity.CRITICAL,
    "blocker": Severity.CRITICAL,
    "high": Severity.HIGH,
    "major": Severity.HIGH,
    "error": Severity.HIGH,
    "medium": Severity.MEDIUM,
    "moderate": Severity.MEDIUM,
    "warning": Severity.MEDIUM,
    "low": Severity.LOW,
    "minor": Severity.LOW,
    "info": Severity.INFO,
    "informational": Severity.INFO,
    "note": Severity.INFO,
}

_CATEGORY_ALIASES = {
    "bug": Category.BUG,
    "defect": Category.BUG,
    "error": Category.BUG,
    "security": Category.SECURITY,
    "vulnerability": Category.SECURITY,
    "code_quality": Category.CODE_QUALITY,
    "quality": Category.CODE_QUALITY,
    "smell": Category.CODE_QUALITY,
    "performance": Category.PERFORMANCE,
    "perf": Category.PERFORMANCE,
    "architecture": Category.ARCHITECTURE,
    "design": Category.ARCHITECTURE,
    "dependency": Category.DEPENDENCY,
    "dependencies": Category.DEPENDENCY,
    "deps": Category.DEPENDENCY,
    "missing_test": Category.MISSING_TEST,
    "test": Category.MISSING_TEST,
    "testing": Category.MISSING_TEST,
    "style": Category.STYLE,
    "convention": Category.STYLE,
}


@dataclass(frozen=True)
class ReviewAgentConfig:
    """ Knobs for one agent invocation (model pinned per agent in Phase 7)."""

    model: str
    temperature: float = 0.2
    max_tokens: int = 4000
    max_findings: int = MAX_FINDINGS
    prompt_version: str = PROMPT_VERSION


def load_prompt_template() -> str:
    """Read the versioned v1 prompt (packaged text, no I/O surprises)."""
    return resources.files("app.prompts.review").joinpath("v1.md").read_text(
        encoding="utf-8"
    )


def build_review_prompt(
    files: list[FileSnapshot],
    deterministic: list[DeterministicFindingIn],
    language: str | None,
) -> tuple[str, str]:
    """Render (system, user) prompts. File bodies are truncated upstream;
    omission is marked so the model never assumes full contents."""
    system_prompt = load_prompt_template()
    lines = [
        "REVIEW SCOPE",
        f"language: {language or 'unknown'}",
        f"files: {len(files)}",
        "",
    ]
    for snapshot in files:
        lines.append(f"--- file: {snapshot.path} ---")
        lines.append(snapshot.content)
        if snapshot.truncated:
            lines.append("[... truncated by the backend; do not assume unseen code ...]")
        lines.append("")
    lines.append(f"DETERMINISTIC FINDINGS: {len(deterministic)}")
    for hit in deterministic:
        line = "" if hit.line is None else f":{hit.line}"
        lines.append(f"- [{hit.tool}] {hit.rule_id} {hit.file}{line}: {hit.message}")
    lines.append("")
    lines.append(
        "TASK: judge each deterministic hit, scan the files for further issues, "
        "and return the single JSON object described in the system prompt."
    )
    return system_prompt, "\n".join(lines)


def normalize_severity(raw: object) -> Severity:
    """Map free-form model output onto the enum; unknown → MEDIUM."""
    if isinstance(raw, Severity):
        return raw
    key = str(raw or "").strip().lower()
    if not key:
        return Severity.MEDIUM
    try:
        return Severity[key.upper()]
    except KeyError:
        return _SEVERITY_ALIASES.get(key, Severity.MEDIUM)


def normalize_category(raw: object) -> Category:
    """Map free-form model output onto the enum; unknown → CODE_QUALITY."""
    if isinstance(raw, Category):
        return raw
    key = str(raw or "").strip().lower().replace(" ", "_").replace("-", "_")
    if not key:
        return Category.CODE_QUALITY
    try:
        return Category[key.upper()]
    except KeyError:
        return _CATEGORY_ALIASES.get(key, Category.CODE_QUALITY)


def normalize_path(raw: object) -> str | None:
    """Flatten a model-emitted path into the scope: posix, no anchors,
    no ``..`` escapes, no drive letters. None when nothing usable remains."""
    if not isinstance(raw, str) or not raw.strip():
        return None
    parts = [
        part
        for part in raw.replace("\\", "/").split("/")
        if part not in ("", ".", "..")
    ]
    if not parts:
        return None
    if re.fullmatch(r"[A-Za-z]:", parts[0]):
        parts = parts[1:]
    if not parts or ":" in parts[0]:
        return None
    return "/".join(parts)


def _coerce_int(raw: object) -> int | None:
    try:
        value = int(str(raw).strip())  # type: ignore[arg-type]
    except (TypeError, ValueError, AttributeError):
        return None
    return value if value >= 0 else None


def _coerce_confidence(raw: object) -> float:
    try:
        value = float(raw)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return 0.0
    return min(1.0, max(0.0, value))


def normalize_raw_item(item: dict) -> dict:
    """Pre-validate one raw finding dict: enum aliases, paths, bounds."""
    normalized = dict(item)
    normalized["severity"] = normalize_severity(item.get("severity")).value
    normalized["category"] = normalize_category(item.get("category")).value
    normalized["file_path"] = normalize_path(item.get("file_path"))
    for key in ("line_start", "line_end"):
        normalized[key] = _coerce_int(item.get(key))
    normalized["confidence"] = _coerce_confidence(item.get("confidence"))
    if isinstance(normalized.get("source"), str):
        source = normalized["source"].strip().upper()
        normalized["source"] = (
            source if source in ("DETERMINISTIC", "AI") else FindingSource.AI.value
        )
    for key in ("title", "description", "evidence", "suggested_fix_hint"):
        value = normalized.get(key)
        if value is None:
            normalized[key] = ""
        elif isinstance(value, str):
            normalized[key] = value.strip()
    return normalized


def parse_review_output(text: str) -> tuple[list[ProposedFinding], list[str]]:
    """Strict-parse model output into findings. Never raises: garbage in →
    ([], [reasons]) so the caller degrades to deterministic-only."""
    import json

    errors: list[str] = []
    stripped = (text or "").strip()
    for fence in ("```json", "```"):
        if stripped.startswith(fence):
            stripped = stripped[len(fence):].strip()
        if stripped.endswith("```"):
            stripped = stripped[:-3].strip()
    try:
        parsed = json.loads(stripped) if stripped else None
    except ValueError as exc:
        return [], [f"output is not JSON: {exc}"]
    if isinstance(parsed, dict):
        items = parsed.get("findings", [])
        if not isinstance(items, list):
            return [], ["'findings' is not a list"]
    elif isinstance(parsed, list):
        items = parsed
    else:
        return [], ["top-level JSON must be an object or array"]
    findings: list[ProposedFinding] = []
    for index, raw in enumerate(items):
        if not isinstance(raw, dict):
            errors.append(f"item {index} is not an object; skipped")
            continue
        try:
            findings.append(ProposedFinding.model_validate(normalize_raw_item(raw)))
        except Exception as exc:
            errors.append(f"item {index} invalid and skipped: {exc}")
    return findings, errors


def echo_deterministic(hits: list[DeterministicFindingIn]) -> list[ProposedFinding]:
    """Verbatim echo of tool hits (source stays DETERMINISTIC). These are the
    backend's ground truth restated for joining — never upgraded, never
    enriched beyond provenance."""
    echoed: list[ProposedFinding] = []
    for hit in hits:
        line = "" if hit.line is None else f":{hit.line}"
        echoed.append(
            ProposedFinding(
                category=Category.CODE_QUALITY,
                severity=Severity.MEDIUM,
                title=hit.rule_id,
                description=hit.message,
                file_path=hit.file,
                line_start=hit.line,
                line_end=hit.line,
                evidence=f"tool:{hit.tool}{line}",
                source=FindingSource.DETERMINISTIC,
                suggested_fix_hint="",
                confidence=1.0,
            )
        )
    return echoed


def finding_key(finding: ProposedFinding) -> str:
    """Dedupe identity within one source: source + rule title + location."""
    line = "" if finding.line_start is None else str(finding.line_start)
    return "|".join(
        [finding.source.value, finding.title, finding.file_path or "", line]
    )


_SEVERITY_RANK = {
    Severity.CRITICAL: 0,
    Severity.HIGH: 1,
    Severity.MEDIUM: 2,
    Severity.LOW: 3,
    Severity.INFO: 4,
}


def _location_key(finding: ProposedFinding) -> str:
    line = "" if finding.line_start is None else str(finding.line_start)
    return f"{finding.file_path or ''}:{line}"


def merge_rank(
    deterministic: list[ProposedFinding],
    ai_findings: list[ProposedFinding],
    max_findings: int = MAX_FINDINGS,
) -> list[ProposedFinding]:
    """Merge echoes + AI hits: drop AI duplicates of deterministic locations
    (deterministic first), drop exact AI duplicates, rank deterministic
    first then severity × confidence, cap the list."""
    merged: list[ProposedFinding] = []
    seen: set[str] = set()
    det_locations = {_location_key(f) for f in deterministic}
    for finding in deterministic:
        key = ("DET", finding_key(finding))
        if key not in seen:
            seen.add(key)
            merged.append(finding)
    ai_unique = [f for f in ai_findings if _location_key(f) not in det_locations]
    ai_unique.sort(
        key=lambda f: (_SEVERITY_RANK.get(f.severity, 2), -f.confidence, f.title)
    )
    for finding in ai_unique:
        key = ("AI", finding_key(finding))
        if key not in seen:
            seen.add(key)
            merged.append(finding)
    return merged[:max(0, max_findings)]


def run_review_agent(
    files: list[FileSnapshot],
    deterministic: list[DeterministicFindingIn],
    language: str | None,
    provider: LLMProvider,
    config: ReviewAgentConfig,
) -> tuple[list[ProposedFinding], list[str]]:
    """One reasoning pass: prompt → single LLM call → strict parse →
    normalize. Transport failures propagate as LLMError (retryable upstream);
    malformed output degrades to ([], reasons)."""
    system_prompt, user_prompt = build_review_prompt(files, deterministic, language)
    response = provider.complete(
        LLMRequest(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            model=config.model,
            temperature=config.temperature,
            max_tokens=config.max_tokens,
        )
    )
    findings, errors = parse_review_output(response.text)
    return findings[: max(0, config.max_findings)], errors
