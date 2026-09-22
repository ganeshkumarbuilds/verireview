"""LLM provider abstraction (OpenRouter-compatible).

Phase 7A defines the interface and the OpenRouter request shape. The
OpenRouter provider makes real network calls in later phases; it retries
transient transport failures (429/5xx/timeout) with backoff, and treats a
null or empty model completion as a retryable failure rather than handing
callers an empty string that fails JSON parsing downstream with a
confusing, generic error.
"""

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
import time

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
    client is injectable so tests use a mock transport.

    Retries transient failures (HTTP 429/5xx, timeouts, connection errors,
    and a null/empty completion body) up to ``max_retries`` times with
    exponential backoff before raising. A completion is only ever handed
    back to the caller once it actually contains non-empty text, so a
    downstream JSON parser never has to guess why an empty string failed.
    """

    # Status codes worth retrying: rate limiting and server-side failures.
    _RETRYABLE_STATUS = {408, 429, 500, 502, 503, 504}

    def __init__(
        self,
        api_key: str,
        base_url: str = "https://openrouter.ai/api/v1",
        timeout_seconds: float = 60.0,
        client: httpx.Client | None = None,
        max_retries: int = 3,
        backoff_seconds: float = 1.5,
    ) -> None:
        if not api_key.strip():
            raise ValueError("OpenRouter API key is required")
        self._api_key = api_key
        self._url = base_url.rstrip("/") + "/chat/completions"
        self._timeout = timeout_seconds
        self._client = client
        self._max_retries = max(1, max_retries)
        self._backoff_seconds = backoff_seconds

    def _headers(self) -> dict:
        return {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }

    def _client_or_default(self) -> httpx.Client:
        if self._client is not None:
            return self._client
        return httpx.Client(timeout=self._timeout)

    def _sleep(self, attempt: int) -> None:
        # Exponential backoff: backoff_seconds * 2^(attempt-1).
        time.sleep(self._backoff_seconds * (2 ** (attempt - 1)))

    def _post_once(self, payload: dict) -> httpx.Response:
        """One HTTP attempt. Raises LLMError only for non-retryable failures;
        retryable failures raise a plain RuntimeError the caller loop catches."""
        try:
            response = self._client_or_default().post(
                self._url, json=payload, headers=self._headers()
            )
        except httpx.TimeoutException as exc:
            raise _RetryableTransportError(f"OpenRouter call timed out: {exc}") from exc
        except httpx.HTTPError as exc:
            raise _RetryableTransportError(f"OpenRouter call failed: {exc}") from exc
        if response.status_code in self._RETRYABLE_STATUS:
            raise _RetryableTransportError(
                f"OpenRouter returned retryable status {response.status_code}"
            )
        try:
            response.raise_for_status()
        except httpx.HTTPError as exc:
            # Non-retryable 4xx (bad request, auth failure, etc.) — fail fast.
            raise LLMError(f"OpenRouter call failed: {exc}") from exc
        return response

    def _extract_text(self, response: httpx.Response) -> tuple[str, dict]:
        try:
            body = response.json()
            choice = body["choices"][0]["message"]["content"]
            usage = body.get("usage", {})
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            raise LLMError(f"OpenRouter returned an unreadable body: {exc}") from exc
        text = choice if isinstance(choice, str) else ""
        return text, {"model": body.get("model"), "usage": usage}

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
        last_error: Exception | None = None
        for attempt in range(1, self._max_retries + 1):
            try:
                response = self._post_once(payload)
                text, meta = self._extract_text(response)
            except _RetryableTransportError as exc:
                last_error = exc
                if attempt < self._max_retries:
                    self._sleep(attempt)
                    continue
                raise LLMError(
                    f"OpenRouter call failed after {self._max_retries} attempts: {exc}"
                ) from exc
            if not text or not text.strip():
                # A null/empty completion is what produces the confusing
                # downstream "Expecting value: line 1 column 1 (char 0)"
                # JSON error. Treat it as retryable here instead.
                last_error = LLMError("OpenRouter returned an empty completion")
                if attempt < self._max_retries:
                    self._sleep(attempt)
                    continue
                raise last_error
            usage = meta["usage"]
            return LLMResponse(
                text=text,
                model=meta["model"] or request.model,
                prompt_tokens=int(usage.get("prompt_tokens", 0)),
                completion_tokens=int(usage.get("completion_tokens", 0)),
            )
        # Unreachable (loop always returns or raises), but keeps type-checkers happy.
        raise LLMError(f"OpenRouter call failed: {last_error}")


class _RetryableTransportError(RuntimeError):
    """Internal signal for a transport failure worth retrying. Never
    escapes OpenRouterProvider.complete — it is always converted to
    LLMError (after retries) or swallowed by a successful retry."""