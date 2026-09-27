from __future__ import annotations

from types import SimpleNamespace

import pytest

from app.agent.compactor import ContextOverflowError
from app.agent.runtime import AgentRuntime


class FakeDb:
    def __init__(self) -> None:
        self.added: list[object] = []

    async def get(self, _model, _key):
        return None

    def add(self, obj) -> None:
        self.added.append(obj)

    async def flush(self) -> None:
        return None


def test_summary_boundary_drops_covered_history() -> None:
    history = [
        {"role": "user", "content": "q1", "message_id": "m1"},
        {"role": "assistant", "content": "a1", "message_id": "m2"},
        {"role": "user", "content": "q2", "message_id": "m3"},
        {"role": "assistant", "content": "a2", "message_id": "m4"},
    ]
    summary, kept = AgentRuntime._apply_summary_boundary(
        history, {"text": "旧摘要", "through_message_id": "m2"}
    )
    assert summary == "旧摘要"
    assert [item["message_id"] for item in kept] == ["m3", "m4"]


def test_summary_without_valid_boundary_is_not_injected() -> None:
    history = [
        {"role": "user", "content": "q1", "message_id": "m1"},
        {"role": "assistant", "content": "a1", "message_id": "m2"},
    ]
    summary, kept = AgentRuntime._apply_summary_boundary(
        history, {"text": "旧摘要", "through_message_id": "run:1"}
    )
    assert summary == ""
    assert kept == history


def test_stored_summary_cannot_replace_the_six_newest_turns() -> None:
    history = [
        message
        for index in range(7)
        for message in (
            {"role": "user", "content": f"q{index}", "message_id": f"u{index}"},
            {"role": "assistant", "content": f"a{index}", "message_id": f"a{index}"},
        )
    ]
    allowed, recent = AgentRuntime._apply_summary_boundary(
        history, {"text": "旧摘要", "through_message_id": "a0"}, keep_recent_turns=6
    )
    assert allowed == "旧摘要"
    assert recent == history[2:]
    rejected, original = AgentRuntime._apply_summary_boundary(
        history, {"text": "旧摘要", "through_message_id": "a1"}, keep_recent_turns=6
    )
    assert rejected == ""
    assert original == history


def test_plan_history_keeps_full_original_when_it_fits() -> None:
    runtime = AgentRuntime.__new__(AgentRuntime)
    full = [
        {"role": "user", "content": "问题一", "message_id": "m1"},
        {"role": "assistant", "content": "回答一", "message_id": "m2"},
    ]
    history, summary, compact_budget = runtime._plan_history(
        full, "旧摘要", full[:1], 100_000, 0, 0, 100
    )
    assert history == full
    assert summary == ""
    assert compact_budget is None


def test_plan_history_uses_summary_when_only_that_fits() -> None:
    runtime = AgentRuntime.__new__(AgentRuntime)
    full = [
        {"role": "user", "content": "中" * 3_000, "message_id": "m1"},
        {"role": "assistant", "content": "文" * 3_000, "message_id": "m2"},
        {"role": "user", "content": "问题二", "message_id": "m3"},
    ]
    probe_text, probe_remaining = AgentRuntime._apply_summary_boundary(
        full, {"text": "旧摘要", "through_message_id": "m2"}
    )
    history, summary, compact_budget = runtime._plan_history(
        full, probe_text, probe_remaining, 2_000, 0, 0, 200
    )
    assert summary == "旧摘要"
    assert history == probe_remaining
    assert compact_budget is None


def test_plan_history_requests_compaction_when_summary_also_overflows() -> None:
    runtime = AgentRuntime.__new__(AgentRuntime)
    full = [
        {"role": "user", "content": "中" * 3_000, "message_id": "m1"},
        {"role": "assistant", "content": "文" * 3_000, "message_id": "m2"},
        {"role": "user", "content": "问" * 3_000, "message_id": "m3"},
    ]
    probe_text, probe_remaining = AgentRuntime._apply_summary_boundary(
        full, {"text": "旧摘要", "through_message_id": "m2"}
    )
    history, summary, compact_budget = runtime._plan_history(
        full, probe_text, probe_remaining, 1_000, 0, 0, 200
    )
    assert summary == "旧摘要"
    assert compact_budget is not None


def test_plan_history_uses_actual_available_budget_below_1000() -> None:
    runtime = AgentRuntime.__new__(AgentRuntime)
    full = [{"role": "user", "content": "中" * 2_000, "message_id": "m1"}]
    history, summary, compact_budget = runtime._plan_history(
        full, "", full, 900, 100, 100, 100
    )
    assert history == full
    assert summary == ""
    assert compact_budget == 700


def test_plan_history_rejects_no_space_for_nonempty_history() -> None:
    runtime = AgentRuntime.__new__(AgentRuntime)
    full = [{"role": "user", "content": "旧对话", "message_id": "m1"}]
    with pytest.raises(ContextOverflowError, match="no room for history"):
        runtime._plan_history(full, "", full, 900, 100, 800, 800)


def test_plan_history_ignores_stored_summary_that_cannot_fit() -> None:
    runtime = AgentRuntime.__new__(AgentRuntime)
    full = [{"role": "user", "content": "中" * 2_000, "message_id": "m1"}]
    history, summary, compact_budget = runtime._plan_history(
        full, "旧摘要", [], 900, 100, 100, 850
    )
    assert history == full
    assert summary == ""
    assert compact_budget == 700


@pytest.mark.asyncio
async def test_save_transcript_strips_system_and_appends_answer() -> None:
    runtime = AgentRuntime.__new__(AgentRuntime)
    db = FakeDb()
    run = SimpleNamespace(id="run-1", conversation_id="conv-1")
    messages = [
        {"role": "system", "content": "ROOT"},
        {"role": "user", "content": "问题"},
        {
            "role": "assistant",
            "content": None,
            "tool_calls": [{"id": "c1", "type": "function", "function": {}}],
        },
        {"role": "tool", "tool_call_id": "c1", "content": "结果"},
    ]
    await runtime._save_transcript(db, run, messages, "最终回答")
    stored = db.added[0].messages
    assert all(item["role"] != "system" for item in stored)
    assert stored[-1] == {"role": "assistant", "content": "最终回答"}
    assert any(item["role"] == "tool" and item["tool_call_id"] == "c1" for item in stored)
