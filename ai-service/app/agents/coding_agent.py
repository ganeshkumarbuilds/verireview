"""Coding Agent reasoning (Phase 9D).

Pure, provider-agnostic logic: prompt building, strict output parsing,
diff validation. The only I/O is one ``LLMProvider.complete`` call.
"""

from dataclasses import dataclass
from importlib import resources
import re

from app.llm import LLMProvider, LLMRequest
from app.schemas.coding import CodingFinding, CodingResult
from app.schemas.review import FileSnapshot

PROMPT_VERSION = "coding/v1"

# Forbidden claims: backend will reject diffs that assert verification.
_VERIFIED_CLAIMS = re.compile(r"\b(verified|tested|build\s+passed|tests?\s+passed)\b", re.I)


@dataclass(frozen=True)
class CodingAgentConfig:
    model: str
    temperature: float = 0.2
    max_tokens: int = 4000
    prompt_version: str = PROMPT_VERSION


def load_coding_prompt_template() -> str:
    return resources.files("app.prompts.coding").joinpath("v1.md").read_text(encoding="utf-8")


def build_coding_prompt(
    files: list[FileSnapshot],
    finding: CodingFinding,
    scope_note: str | None,
    language: str | None,
) -> tuple[str, str]:
    system_prompt = load_coding_prompt_template()
    lines = [
        "CODING SCOPE",
        f"language: {language or 'unknown'}",
        f"files: {len(files)}",
        "",
        "FINDING TO FIX",
        f"id: {finding.id}",
        f"title: {finding.title}",
        f"description: {finding.description or ''}",
        f"file: {finding.file_path or 'unknown'}:{finding.line_start or ''}",
        f"category: {finding.category} severity: {finding.severity}",
        f"evidence: {finding.evidence or ''}",
        "",
        f"scope_note: {scope_note or ''}",
        "",
    ]
    for snapshot in files:
        lines.append(f"--- file: {snapshot.path} ---")
        lines.append(snapshot.content)
        if snapshot.truncated:
            lines.append("[... truncated by the backend; do not assume unseen code ...]")
        lines.append("")
    lines.append("TASK: produce the single JSON object described in the system prompt.")
    return system_prompt, "\n".join(lines)


def _count_diff_stats(diff: str) -> tuple[int, int, int]:
    files = set()
    additions = deletions = 0
    for line in diff.splitlines():
        if line.startswith("diff --git"):
            # diff --git a/path b/path
            parts = line.split()
            if len(parts) >= 4:
                files.add(parts[3][2:] if parts[3].startswith("b/") else parts[3])
        elif line.startswith("+++ ") or line.startswith("--- "):
            continue
        elif line.startswith("+") and not line.startswith("+++"):
            additions += 1
        elif line.startswith("-") and not line.startswith("---"):
            deletions += 1
    return len(files), additions, deletions


def validate_diff(diff: str) -> list[str]:
    """Validate unified diff shape. Returns list of errors (empty = valid)."""
    errors: list[str] = []
    stripped = (diff or "").strip()
    if not stripped:
        errors.append("diff is empty")
        return errors
    if len(stripped) > 200_000:
        errors.append("diff exceeds 200KB limit")
    if not ("diff --git" in stripped and "--- a/" in stripped and "+++ b/" in stripped and "@@" in stripped):
        errors.append("diff is not valid unified format (missing diff --git / --- / +++ / @@)")
    if _VERIFIED_CLAIMS.search(stripped):
        errors.append("diff must not claim verification (tested/verified/build passed)")
    # Path traversal check
    for line in stripped.splitlines():
        if line.startswith("Binary files ") or line.startswith("GIT binary patch"):
            errors.append("diff must not contain binary content")
        if line.startswith("--- a/") or line.startswith("+++ b/"):
            path = line[6:].strip().split()[0] if len(line) > 6 else ""
            if (
                ".." in path
                or path.startswith("/")
                or "\\" in path
                or re.match(r"^[A-Za-z]:", path)
            ):
                errors.append(f"diff contains illegal path: {path}")
        if line.startswith("diff --git"):
            if ".." in line or "\\" in line:
                errors.append("diff contains illegal path traversal")
    files, adds, dels = _count_diff_stats(stripped)
    if files > 5:
        errors.append(f"diff touches {files} files, limit is 5")
    if adds + dels > 200:
        errors.append(f"diff has {adds + dels} changed lines, limit is 200")
    return errors


def parse_coding_output(text: str) -> tuple[str, str, list[str]]:
    """Strict-parse model output. Returns (diff, explanation, errors)."""
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
        return "", "", [f"output is not JSON: {exc}"]
    if not isinstance(parsed, dict):
        return "", "", ["top-level JSON must be an object"]
    diff = parsed.get("diff")
    explanation = parsed.get("explanation", "")
    if not isinstance(diff, str) or not diff.strip():
        return "", "", ["'diff' is missing or empty"]
    if not isinstance(explanation, str):
        explanation = str(explanation)
    # Validate diff shape
    diff_errors = validate_diff(diff)
    if diff_errors:
        return "", "", diff_errors
    return diff.strip(), explanation.strip()[:5000], []


def run_coding_agent(
    files: list[FileSnapshot],
    finding: CodingFinding,
    scope_note: str | None,
    language: str | None,
    provider: LLMProvider,
    config: CodingAgentConfig,
) -> tuple[str, str, list[str]]:
    """One reasoning pass: prompt → LLM → strict parse → validate."""
    system_prompt, user_prompt = build_coding_prompt(files, finding, scope_note, language)
    response = provider.complete(
        LLMRequest(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            model=config.model,
            temperature=config.temperature,
            max_tokens=config.max_tokens,
        )
    )
    diff, explanation, errors = parse_coding_output(response.text)
    if errors:
        # Caller decides to surface as controlled failure
        return "", "", errors
    return diff, explanation, []
