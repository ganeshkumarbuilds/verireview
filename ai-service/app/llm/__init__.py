"""LLM provider abstraction (OpenRouter-compatible).

Phase 7A defines the interface and the OpenRouter request shape only —
NO real calls are made (no network in unit tests; the OpenRouter provider
is exercised against an httpx mock transport). Real invocations arrive
with Review Agent reasoning in a later phase.
"""

from abc import ABC, abstractmethod
from dataclasses import dataclass, field

import httpx


@dataclass(frozen=True)
class LLMRequest:
    """Model-agnostic chat request."""

    system_prompt: str
    user_prompt: str
    model: str
    temperature: float = 0.2
    max_tokens: int = 2000
    extra: dict = field(default_factory=dict)


@dataclass(frozen=True)
class LLMResponse:
    """Model-agnostic chat response (usage for token-budget logging)."""

    text: str
    model: str
    prompt_tokens: int = 0
    completion_tokens: int = 0


class LLMProvider(ABC):
    """Single interface every agent uses. Backends: OpenRouter (Phase 7+),
    Fake (tests/evals)."""

    @abstractmethod
    def complete(self, request: LLMRequest) -> LLMResponse:
        """Run one chat completion. Raises LLMError on transport failure."""
        raise NotImplementedError


class LLMError(RuntimeError):
    """Transport or protocol failure (retryable: 429/5xx/timeout)."""


class FakeProvider(LLMProvider):
    """Deterministic stand-in for tests and offline evals. Records calls."""

    def __init__(self, text: str = "{}") -> None:
        self.text = text
        self.calls: list[LLMRequest] = []

    def complete(self, request: LLMRequest) -> LLMResponse:
        self.calls.append(request)
        return LLMResponse(text=self.text, model=request.model)


class NullProvider(FakeProvider):
    """No-LLM fallback: yields zero AI findings. Used by the endpoint when
    no API key is configured, so the contract stays stable offline."""

    def __init__(self) -> None:
        super().__init__(text='{"findings": []}')


class OpenRouterProvider(LLMProvider):
    """OpenRouter chat-completions provider (OpenAI-compatible wire format).

    Constructed from explicit settings (never module globals); the httpx
    client is injectable so tests use a mock transport. Not called in 7A.
    """

    def __init__(
        self,
        api_key: str,
        base_url: str = "https://openrouter.ai/api/v1",
        timeout_seconds: float = 60.0,
        client: httpx.Client | None = None,
    ) -> None:
        if not api_key.strip():
            raise ValueError("OpenRouter API key is required")
        self._api_key = api_key
        self._url = base_url.rstrip("/") + "/chat/completions"
        self._timeout = timeout_seconds
        self._client = client

    def _headers(self) -> dict:
        return {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }

    def _client_or_default(self) -> httpx.Client:
        if self._client is not None:
            return self._client
        return httpx.Client(timeout=self._timeout)

    def complete(self, request: LLMRequest) -> LLMResponse:
        payload = {
            "model": request.model,
            "temperature": request.temperature,
            "max_tokens": request.max_tokens,
            "messages": [
                {"role": "system", "content": request.system_prompt},
                {"role": "user", "content": request.user_prompt},
            ],
        }
        try:
            response = self._client_or_default().post(
                self._url, json=payload, headers=self._headers()
            )
            response.raise_for_status()
        except (httpx.HTTPError, ValueError) as exc:
            raise LLMError(f"OpenRouter call failed: {exc}") from exc
        try:
            body = response.json()
            choice = body["choices"][0]["message"]["content"]
            usage = body.get("usage", {})
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            raise LLMError(f"OpenRouter returned an unreadable body: {exc}") from exc
        return LLMResponse(
            text=choice if isinstance(choice, str) else str(choice),
            model=body.get("model", request.model),
            prompt_tokens=int(usage.get("prompt_tokens", 0)),
            completion_tokens=int(usage.get("completion_tokens", 0)),
        )
