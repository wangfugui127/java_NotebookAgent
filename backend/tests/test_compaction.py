from __future__ import annotations

import math
from types import SimpleNamespace
from typing import Any

import pytest

from app.agent.compactor import SUMMARY_PREFIX, ContextCompactor, ContextOverflowError
from app.agent.context_builder import estimate_tokens, messages_tokens
from app.agent.messages import TOOL_RESULT_PLACEHOLDER
from app.agent.runtime import AgentRuntime
from app.core.config import Settings
from app.providers.deepseek import ProviderResponse


class FakeProvider:
    def __init__(self, content: str = "结构化摘要", fail: bool = False) -> None:
        self.content = content
        self.fail = fail
        self.calls = 0
        self.requests: list[list[dict[str, Any]]] = []

    async def invoke(self, messages: list[dict[str, Any]], tools: list[Any], _cb: Any = None):
        self.calls += 1
        self.requests.append(messages)
        if self.fail:
            raise RuntimeError("provider down")
        return ProviderResponse(content=self.content)


def settings(input_budget: int = 1_800, *, keep_results: int = 1) -> Settings:
    return Settings(
        model_context_window=math.ceil((input_budget + 100) / 0.95),
        max_output_tokens=100,
        context_safety_ratio=0.95,
        context_keep_recent_turns=6,
        context_keep_recent_tool_results=keep_results,
    )


def turn(index: int, size: int = 400) -> list[dict[str, Any]]:
    return [
        {"role": "user", "content": f"问题{index}:" + "中" * size},
        {
            "role": "assistant",
            "content": None,
            "tool_calls": [
                {
                    "id": f"call-{index}",
                    "type": "function",
                    "function": {"name": "search_notebook", "arguments": "{}"},
                }
            ],
        },
        {"role": "tool", "tool_call_id": f"call-{index}", "content": "工具结果" + "文" * size},
        {"role": "assistant", "content": f"回答{index}"},
    ]


def messages(turns: int, size: int = 400) -> list[dict[str, Any]]:
    system = [{"role": "system", "content": "ROOT PROMPT"}]
    convo = [item for index in range(turns) for item in turn(index, size)]
    return [*system, *convo]


def clear_first_tool_results(data: list[dict[str, Any]], count: int) -> list[dict[str, Any]]:
    copied = [dict(item) for item in data]
    for item in [item for item in copied if item.get("role") == "tool"][:count]:
        item["content"] = TOOL_RESULT_PLACEHOLDER
    return copied


def summary_budget(data: list[dict[str, Any]]) -> int:
    recent = [data[0], *data[-24:]]
    summary_tokens = estimate_tokens(str({"role": "system", "content": "摘要"}))
    return messages_tokens(recent) + summary_tokens + 20


@pytest.mark.asyncio
async def test_small_context_is_untouched() -> None:
    compactor = ContextCompactor(settings(), FakeProvider())
    original = messages(1, 10)
    result, omissions, summary = await compactor.compact(original)
    assert result == original
    assert omissions == []
    assert summary is None


@pytest.mark.asyncio
async def test_tool_cleanup_selects_the_smallest_oldest_batch() -> None:
    provider = FakeProvider()
    data = messages(8, size=120)
    budget = messages_tokens(clear_first_tool_results(data, 1)) + 2
    compactor = ContextCompactor(settings(budget, keep_results=0), provider)
    result, omissions, _ = await compactor.compact(data)
    tools = [item for item in result if item.get("role") == "tool"]
    assert tools[0]["content"] == TOOL_RESULT_PLACEHOLDER
    assert all(item["content"] != TOOL_RESULT_PLACEHOLDER for item in tools[1:])
    assert result[-24:] == data[-24:]
    assert provider.calls == 0
    assert omissions


@pytest.mark.asyncio
async def test_tool_cleanup_can_select_two_without_clearing_recent_turns() -> None:
    provider = FakeProvider()
    data = messages(8, size=120)
    budget = messages_tokens(clear_first_tool_results(data, 2)) + 2
    compactor = ContextCompactor(settings(budget, keep_results=0), provider)
    result, _, _ = await compactor.compact(data)
    tools = [item for item in result if item.get("role") == "tool"]
    assert [item["content"] == TOOL_RESULT_PLACEHOLDER for item in tools] == [
        True, True, False, False, False, False, False, False
    ]
    assert result[-24:] == data[-24:]
    assert provider.calls == 0


@pytest.mark.asyncio
async def test_next_overflow_skips_tool_cleanup_and_summarizes_old_turns() -> None:
    provider = FakeProvider(content="目标：研究\n关键证据：[evidence:abc]")
    data = messages(8, size=120)
    compactor = ContextCompactor(settings(summary_budget(data), keep_results=0), provider)
    result, omissions, summary = await compactor.compact(data, tool_cleanup_done=True)
    assert provider.calls >= 1
    assert summary is not None
    assert not any("cleared old tool" in item["reason"] for item in omissions)
    assert result[-24:] == data[-24:]
    assert any(
        str(item.get("content", "")).startswith(SUMMARY_PREFIX) for item in result
    )
    assert "[evidence:abc]" in str(result)


@pytest.mark.asyncio
async def test_runtime_advances_persisted_run_stage_after_first_cleanup() -> None:
    provider = FakeProvider(content="摘要")
    data = messages(8, size=120)
    budget = messages_tokens(clear_first_tool_results(data, 1)) + 2
    runtime = AgentRuntime.__new__(AgentRuntime)
    runtime.settings = settings(budget, keep_results=0)
    runtime.compactor = ContextCompactor(runtime.settings, provider)
    run = SimpleNamespace(state={})
    first, _ = await runtime._compact_working_messages(run, data)
    assert run.state["tool_cleanup_done"] is True
    assert provider.calls == 0

    second, omissions = await runtime._compact_working_messages(
        run, [*first, *turn(8, size=120)]
    )
    assert provider.calls >= 1
    assert not any("cleared old tool" in item["reason"] for item in omissions)
    assert second[-4:] == turn(8, size=120)


@pytest.mark.asyncio
async def test_tool_schema_overflow_is_reported_before_model_call() -> None:
    runtime = AgentRuntime.__new__(AgentRuntime)
    runtime.settings = settings(1_800)
    runtime.compactor = ContextCompactor(runtime.settings, FakeProvider())
    run = SimpleNamespace(state={})

    with pytest.raises(ContextOverflowError, match="工具 schema"):
        await runtime._compact_working_messages(
            run, [{"role": "user", "content": "问题"}],
            schema_tokens=runtime.settings.effective_input_budget,
        )


@pytest.mark.asyncio
async def test_existing_summary_is_merged_without_duplicate_prefix() -> None:
    provider = FakeProvider(content="旧摘要与新摘要")
    data = [
        {"role": "system", "content": "ROOT PROMPT"},
        {"role": "system", "content": f"{SUMMARY_PREFIX}\n旧摘要"},
        *messages(8, size=120)[1:],
    ]
    compactor = ContextCompactor(settings(summary_budget(data), keep_results=0), provider)
    result, _, _ = await compactor.compact(data, tool_cleanup_done=True)
    assert provider.calls >= 1
    assert "旧摘要" in str(provider.requests[0])
    assert sum(str(item.get("content") or "").startswith(SUMMARY_PREFIX) for item in result) == 1
    assert "旧摘要与新摘要" in str(result)


@pytest.mark.asyncio
async def test_provider_failure_does_not_pass_oversized_messages_to_model() -> None:
    data = messages(8, size=120)
    compactor = ContextCompactor(
        settings(summary_budget(data), keep_results=0), FakeProvider(fail=True)
    )
    with pytest.raises(ContextOverflowError, match="summary failed.*over budget"):
        await compactor.compact(data, tool_cleanup_done=True)


@pytest.mark.asyncio
async def test_six_recent_turns_are_never_cleared_or_summarized() -> None:
    provider = FakeProvider()
    compactor = ContextCompactor(settings(), provider)
    with pytest.raises(ContextOverflowError, match="six recent turns protected"):
        await compactor.compact(messages(6, size=400))
    assert provider.calls == 0


def history_messages(turns: int, size: int = 400) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for index in range(turns):
        out.append(
            {"role": "user", "content": f"问题{index}:" + "中" * size, "message_id": f"m{index}u"}
        )
        out.append(
            {"role": "assistant", "content": "回答" + "文" * size, "message_id": f"m{index}a"}
        )
    return out


@pytest.mark.asyncio
async def test_compact_history_untouched_within_budget() -> None:
    compactor = ContextCompactor(settings(), FakeProvider())
    data = history_messages(2, 10)
    kept, summary, boundary = await compactor.compact_history(data, 100_000)
    assert kept == data
    assert summary is None
    assert boundary is None


@pytest.mark.asyncio
async def test_compact_history_summarizes_only_the_older_messages() -> None:
    provider = FakeProvider(content="旧对话摘要")
    compactor = ContextCompactor(settings(), provider)
    data = history_messages(8, 80)
    budget = messages_tokens(data[-12:]) + estimate_tokens(str({"text": "旧对话摘要"})) + 20
    kept, summary, boundary = await compactor.compact_history(data, budget)
    assert summary and "旧对话摘要" in summary
    assert provider.calls >= 1
    assert boundary is not None
    assert boundary == "m1a"
    assert kept == data[-12:]
    assert all(item.get("role") in {"user", "assistant"} for item in kept)


@pytest.mark.asyncio
async def test_compact_history_converges_within_budget() -> None:
    compactor = ContextCompactor(settings(), FakeProvider(content="摘要"))
    data = history_messages(8, 80)
    budget = messages_tokens(data[-12:]) + estimate_tokens(str({"text": "摘要"})) + 20
    kept, summary, _boundary = await compactor.compact_history(data, budget)
    assert summary is not None
    assert messages_tokens(kept) + estimate_tokens(str({"text": summary})) <= budget


@pytest.mark.asyncio
async def test_compact_history_raises_when_even_summary_cannot_fit() -> None:
    compactor = ContextCompactor(settings(), FakeProvider(content="摘要"))
    data = [{"role": "user", "content": "中" * 5_000, "message_id": "m1"}]
    with pytest.raises(ContextOverflowError):
        await compactor.compact_history(data, 5)


@pytest.mark.asyncio
async def test_compact_history_keeps_all_six_turns_even_if_over_budget() -> None:
    provider = FakeProvider()
    compactor = ContextCompactor(settings(), provider)
    with pytest.raises(ContextOverflowError, match="newest 6 turns protected"):
        await compactor.compact_history(history_messages(6, 400), 500)
    assert provider.calls == 0


@pytest.mark.asyncio
async def test_compact_raises_when_current_turn_exceeds_budget() -> None:
    compactor = ContextCompactor(settings(), FakeProvider(content="摘要"))
    data = [{"role": "system", "content": "ROOT"}, *turn(0, size=5_000)]
    with pytest.raises(ContextOverflowError):
        await compactor.compact(data)


@pytest.mark.asyncio
async def test_current_user_input_is_never_dropped() -> None:
    compactor = ContextCompactor(settings(), FakeProvider())
    data = messages(6)
    with pytest.raises(ContextOverflowError):
        await compactor.compact(data)
    assert data[-1]["content"] == "回答5"
    assert any(str(item.get("content") or "").startswith("问题5") for item in data)
