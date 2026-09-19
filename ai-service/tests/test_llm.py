"""LLM abstraction tests: interface shape, OpenRouter wire format via mock
transport (no real network), and validation of missing keys."""

import httpx
import pytest

from app.llm import FakeProvider, LLMError, LLMRequest, OpenRouterProvider


def test_fake_provider_records_calls_without_network():
    provider = FakeProvider(text='{"ok": true}')
    response = provider.complete(
        LLMRequest(system_prompt="s", user_prompt="u", model="test/model")
    )
    assert response.text == '{"ok": true}'
    assert response.model == "test/model"
    assert len(provider.calls) == 1


def test_openrouter_shapes_chat_completions_request():
    seen: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["auth"] = request.headers.get("authorization")
        return httpx.Response(
            200,
            json={
                "model": "some/model",
                "choices": [{"message": {"content": "hi"}}],
                "usage": {"prompt_tokens": 3, "completion_tokens": 1},
            },
        )

    client = httpx.Client(transport=httpx.MockTransport(handler))
    provider = OpenRouterProvider(api_key="k", client=client)
    response = provider.complete(
        LLMRequest(system_prompt="sys", user_prompt="user", model="some/model")
    )
    assert seen["url"].endswith("/chat/completions")
    assert seen["auth"] == "Bearer k"
    assert response.text == "hi"
    assert (response.prompt_tokens, response.completion_tokens) == (3, 1)


def test_openrouter_http_error_becomes_llm_error():
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(429, json={"error": "slow down"})

    client = httpx.Client(transport=httpx.MockTransport(handler))
    provider = OpenRouterProvider(api_key="k", client=client)
    with pytest.raises(LLMError):
        provider.complete(LLMRequest(system_prompt="s", user_prompt="u", model="m"))


def test_openrouter_requires_a_key():
    with pytest.raises(ValueError):
        OpenRouterProvider(api_key="   ")
