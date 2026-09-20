"""Generation Agent reasoning.

Pure, provider-agnostic logic: prompt building, strict output parsing,
path validation. The only I/O is one ``LLMProvider.complete`` call per
step (plan, then files). Never touches a database or filesystem.
"""

from dataclasses import dataclass
from importlib import resources
import json
import re

from app.llm import LLMProvider, LLMRequest
from app.schemas.generation import FilesRequest, GeneratedFile, PlannedFile, PlanRequest

PROMPT_VERSION = "generation/v1"

MAX_FILES = 80
MAX_CHARS_PER_FILE = 100_000
MAX_ARCHITECTURE_CHARS = 10000
MAX_APIS_CHARS = 10000
MAX_DEPENDENCIES = 200
MAX_DEPENDENCY_CHARS = 500
MAX_DIRECTORIES = 200
MAX_STEPS = 100
MAX_STEP_CHARS = 2000

_DRIVE_LETTER = re.compile(r"^[A-Za-z]:")


@dataclass(frozen=True)
class PlanSections:
    """Structured plan metadata (architecture, stack fit, build order)."""

    architecture: str = ""
    dependencies: tuple[str, ...] = ()
    directories: tuple[str, ...] = ()
    apis: str = ""
    steps: tuple[str, ...] = ()


@dataclass(frozen=True)
class GenerationAgentConfig:
    model: str
    temperature: float = 0.2
    max_tokens: int = 8000
    prompt_version: str = PROMPT_VERSION


def load_generation_prompt_template() -> str:
    return resources.files("app.prompts.generation").joinpath("v1.md").read_text(encoding="utf-8")


def _stack_lines(stack: dict) -> list[str]:
    lines = [
        f"backend: {stack.get('backend', 'unknown')}",
        f"frontend: {stack.get('frontend', 'unknown')}",
        f"database: {stack.get('database', 'unknown')}",
    ]
    for key in ("db_host", "db_port", "db_name", "db_username", "db_ssl_mode"):
        value = stack.get(key)
        if value not in (None, ""):
            lines.append(f"{key}: {value}")
    return lines


def build_plan_prompt(request: PlanRequest) -> tuple[str, str]:
    system_prompt = load_generation_prompt_template()
    user_lines = [
        "GENERATION SCOPE",
        f"requirement: {request.requirement}",
        *_stack_lines(request.model_dump(mode="json")),
        "",
        "TASK: produce the single PLAN JSON object described in the system prompt.",
    ]
    return system_prompt, "\n".join(user_lines)


def build_files_prompt(request: FilesRequest) -> tuple[str, str]:
    system_prompt = load_generation_prompt_template()
    user_lines = [
        "GENERATION SCOPE",
        f"requirement: {request.requirement}",
        *_stack_lines(request.model_dump(mode="json")),
        "",
        "APPROVED PLAN (generate exactly these files, no more, no fewer):",
    ]
    for item in request.plan:
        user_lines.append(f"- {item.path} :: {item.purpose}")
    user_lines += [
        "",
        "TASK: produce the single FILES JSON object described in the system prompt.",
    ]
    return system_prompt, "\n".join(user_lines)


def validate_generated_path(path: str | None) -> list[str]:
    """Project-relative jail check for AI-produced paths."""
    if not path or not path.strip():
        return ["file has an empty path"]
    candidate = path.strip()
    if candidate.startswith("/") or "\\" in candidate or _DRIVE_LETTER.match(candidate):
        return [f"file has an illegal absolute path: {candidate}"]
    for segment in candidate.split("/"):
        if segment in ("", ".", ".."):
            return [f"file has an illegal path: {candidate}"]
    for char in candidate:
        if char == "\0" or (ord(char) < 0x20 and char != "\t"):
            return [f"file has an illegal path: {candidate}"]
    return []


def _strip_fences(text: str) -> str:
    stripped = (text or "").strip()
    for fence in ("```json", "```"):
        if stripped.startswith(fence):
            stripped = stripped[len(fence):].strip()
        if stripped.endswith("```"):
            stripped = stripped[:-3].strip()
    return stripped


def _parse_string_list(
    value: object, field: str, max_items: int, max_chars: int
) -> tuple[tuple[str, ...], list[str]]:
    """Strict string-list section: wrong types and oversize entries are errors."""
    if value is None:
        return (), []
    if not isinstance(value, list):
        return (), [f"plan '{field}' must be a list"]
    if len(value) > max_items:
        return (), [f"plan '{field}' has {len(value)} entries, limit is {max_items}"]
    items: list[str] = []
    for entry in value:
        if not isinstance(entry, str) or not entry.strip():
            return (), [f"plan '{field}' entries must be non-empty strings"]
        if len(entry) > max_chars:
            return (), [f"plan '{field}' entry exceeds {max_chars} chars"]
        items.append(entry.strip())
    return tuple(items), []


def parse_plan_output(
    text: str,
) -> tuple[list[PlannedFile], PlanSections, str, list[str]]:
    """Strict-parse the plan step. Returns (files, sections, notes, errors)."""
    empty_sections = PlanSections()
    try:
        parsed = json.loads(_strip_fences(text)) if _strip_fences(text) else None
    except ValueError as exc:
        return [], empty_sections, "", [f"plan output is not JSON: {exc}"]
    if not isinstance(parsed, dict):
        return [], empty_sections, "", ["plan top-level JSON must be an object"]
    raw_files = parsed.get("files")
    if not isinstance(raw_files, list) or not raw_files:
        return [], empty_sections, "", ["plan 'files' is missing or empty"]
    if len(raw_files) > MAX_FILES:
        return [], empty_sections, "", [f"plan proposes {len(raw_files)} files, limit is {MAX_FILES}"]
    notes = parsed.get("notes", "")
    files: list[PlannedFile] = []
    seen: set[str] = set()
    for item in raw_files:
        if not isinstance(item, dict):
            return [], empty_sections, "", ["plan file entry must be an object"]
        path = item.get("path")
        errors = validate_generated_path(path if isinstance(path, str) else None)
        if errors:
            return [], empty_sections, "", errors
        normalized = str(path).strip()
        if normalized in seen:
            return [], empty_sections, "", [f"plan duplicates path: {normalized}"]
        seen.add(normalized)
        purpose = item.get("purpose", "")
        files.append(
            PlannedFile(
                path=normalized,
                purpose=str(purpose)[:2000] if isinstance(purpose, str) else "",
            )
        )
    architecture = parsed.get("architecture", "")
    if architecture is None:
        architecture = ""
    if not isinstance(architecture, str):
        return [], empty_sections, "", ["plan 'architecture' must be a string"]
    if len(architecture) > MAX_ARCHITECTURE_CHARS:
        return [], empty_sections, "", ["plan 'architecture' exceeds size limit"]
    apis = parsed.get("apis", "")
    if apis is None:
        apis = ""
    if not isinstance(apis, str):
        return [], empty_sections, "", ["plan 'apis' must be a string"]
    if len(apis) > MAX_APIS_CHARS:
        return [], empty_sections, "", ["plan 'apis' exceeds size limit"]
    dependencies, errors = _parse_string_list(
        parsed.get("dependencies"), "dependencies", MAX_DEPENDENCIES, MAX_DEPENDENCY_CHARS
    )
    if errors:
        return [], empty_sections, "", errors
    raw_directories, errors = _parse_string_list(
        parsed.get("directories"), "directories", MAX_DIRECTORIES, 1000
    )
    if errors:
        return [], empty_sections, "", errors
    directories: list[str] = []
    for directory in raw_directories:
        normalized_dir = directory.rstrip("/")
        errors = validate_generated_path(normalized_dir or None)
        if errors:
            return [], empty_sections, "", [f"plan directory is not project-relative: {directory}"]
        directories.append(normalized_dir)
    steps, errors = _parse_string_list(parsed.get("steps"), "steps", MAX_STEPS, MAX_STEP_CHARS)
    if errors:
        return [], empty_sections, "", errors
    notes_text = str(notes)[:2000] if isinstance(notes, str) else ""
    sections = PlanSections(
        architecture=architecture.strip(),
        dependencies=dependencies,
        directories=tuple(directories),
        apis=apis.strip(),
        steps=steps,
    )
    return files, sections, notes_text, []


def parse_files_output(
    text: str, planned_paths: list[str]
) -> tuple[list[GeneratedFile], str, list[str]]:
    """Strict-parse the files step. Every planned path needs exactly one file."""
    try:
        parsed = json.loads(_strip_fences(text)) if _strip_fences(text) else None
    except ValueError as exc:
        return [], "", [f"files output is not JSON: {exc}"]
    if not isinstance(parsed, dict):
        return [], "", ["files top-level JSON must be an object"]
    raw_files = parsed.get("files")
    if not isinstance(raw_files, list) or not raw_files:
        return [], "", ["files 'files' is missing or empty"]
    if len(raw_files) > MAX_FILES:
        return [], "", [f"generation returned {len(raw_files)} files, limit is {MAX_FILES}"]
    notes = parsed.get("notes", "")
    by_path: dict[str, GeneratedFile] = {}
    for item in raw_files:
        if not isinstance(item, dict):
            return [], "", ["file entry must be an object"]
        path = item.get("path")
        errors = validate_generated_path(path if isinstance(path, str) else None)
        if errors:
            return [], "", errors
        normalized = str(path).strip()
        if normalized in by_path:
            return [], "", [f"generation duplicates path: {normalized}"]
        content = item.get("content")
        if not isinstance(content, str) or not content:
            return [], "", [f"file has no content: {normalized}"]
        if len(content) > MAX_CHARS_PER_FILE:
            return [], "", [f"file is too large: {normalized}"]
        if "\0" in content:
            return [], "", [f"file is not text: {normalized}"]
        language = item.get("language", "")
        by_path[normalized] = GeneratedFile(
            path=normalized,
            content=content,
            language=str(language)[:50] if isinstance(language, str) else "",
        )
    planned = [p.strip() for p in planned_paths]
    missing = [p for p in planned if p not in by_path]
    if missing:
        return [], "", [f"generation is missing planned files: {', '.join(missing[:5])}"]
    extra = [p for p in by_path if p not in set(planned)]
    if extra:
        return [], "", [f"generation adds unplanned files: {', '.join(extra[:5])}"]
    ordered = [by_path[p] for p in planned]
    notes_text = str(notes)[:2000] if isinstance(notes, str) else ""
    return ordered, notes_text, []


def run_plan_agent(
    request: PlanRequest,
    provider: LLMProvider,
    config: GenerationAgentConfig,
) -> tuple[list[PlannedFile], PlanSections, str, list[str]]:
    """One reasoning pass for the plan step: prompt → LLM → strict parse."""
    system_prompt, user_prompt = build_plan_prompt(request)
    response = provider.complete(
        LLMRequest(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            model=config.model,
            temperature=config.temperature,
            max_tokens=config.max_tokens,
        )
    )
    return parse_plan_output(response.text)


def run_files_agent(
    request: FilesRequest,
    provider: LLMProvider,
    config: GenerationAgentConfig,
) -> tuple[list[GeneratedFile], str, list[str]]:
    """One reasoning pass for the files step: prompt → LLM → strict parse."""
    system_prompt, user_prompt = build_files_prompt(request)
    response = provider.complete(
        LLMRequest(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            model=config.model,
            temperature=config.temperature,
            max_tokens=config.max_tokens,
        )
    )
    return parse_files_output(response.text, [item.path for item in request.plan])
