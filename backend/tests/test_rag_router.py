from __future__ import annotations

from types import SimpleNamespace

import pytest

from app.agent.compactor import ContextOverflowError
from app.agent.context_builder import ContextBuilder
from app.agent.rag_router import RAG_ROUTER_SYSTEM_PROMPT, LLMRagRouter
from app.agent.runtime import AgentRuntime
from app.agent.types import AgentState
from app.core.config import Settings
from app.models import RunStatus
from app.retrieval.types import GraphEntity, GraphPlan


class FakeProvider:
    def __init__(self, content: str = "", error: Exception | None = None) -> None:
        self.content = content
        self.error = error
        self.calls: list[tuple[list[dict[str, str]], list[object]]] = []

    async def invoke(self, messages, tools):
        self.calls.append((messages, tools))
        if self.error:
            raise self.error
        return SimpleNamespace(content=self.content)


@pytest.mark.asyncio
@pytest.mark.parametrize("decision", ["rag", "no_rag", "uncertain"])
async def test_rag_router_uses_only_system_prompt_and_current_query(decision: str) -> None:
    provider = FakeProvider(
        f'{{"route":"{decision}","use_graph":false,"entities":[],"intents":[]}}'
    )

    result = await LLMRagRouter(provider).route("比较 Notebook 论文的方法")

    assert result.route == decision
    assert result.graph == GraphPlan()
    assert provider.calls == [
        (
            [
                {"role": "system", "content": RAG_ROUTER_SYSTEM_PROMPT},
                {"role": "user", "content": "比较 Notebook 论文的方法"},
            ],
            [],
        )
    ]


@pytest.mark.asyncio
@pytest.mark.parametrize("content", ["not json", '[]'])
async def test_invalid_rag_route_becomes_uncertain(content: str) -> None:
    assert (await LLMRagRouter(FakeProvider(content)).route("这篇呢？")).route == "uncertain"


@pytest.mark.asyncio
async def test_rag_route_provider_failure_becomes_uncertain() -> None:
    assert (
        await LLMRagRouter(FakeProvider(error=OSError("provider unavailable"))).route("论文")
    ).route == "uncertain"


@pytest.mark.asyncio
async def test_rag_router_extracts_typed_graph_plan_in_one_call() -> None:
    provider = FakeProvider(
        '{"route":"rag","use_graph":true,"entities":['
        '{"name":"A","kind":"paper"},{"name":"B","kind":"paper"},'
        '{"name":"X","kind":"dataset"}],'
        '"intents":["dataset","method","result"]}'
    )
    decision = await LLMRagRouter(provider).route("比较 A、B 在 X 上的方法和结果")

    assert decision.route == "rag"
    assert decision.graph == GraphPlan(
        True,
        (GraphEntity("A", "paper"), GraphEntity("B", "paper"), GraphEntity("X", "dataset")),
        ("dataset", "method", "result"),
    )
    assert len(provider.calls) == 1


@pytest.mark.asyncio
async def test_invalid_graph_kind_keeps_vector_rag_but_skips_graph() -> None:
    decision = await LLMRagRouter(
        FakeProvider(
            '{"route":"rag","use_graph":true,"entities":'
            '[{"name":"X","kind":"unknown"}],"intents":["dataset"]}'
        )
    ).route("X")
    assert decision.route == "rag"
    assert decision.graph == GraphPlan()


@pytest.mark.asyncio
async def test_legacy_rag_route_still_runs_vector_search() -> None:
    decision = await LLMRagRouter(FakeProvider('{"route":"rag"}')).route("论文")
    assert decision.route == "rag"
    assert decision.graph == GraphPlan()


@pytest.mark.asyncio
@pytest.mark.parametrize("decision", ["no_rag", "uncertain"])
async def test_initial_rag_evidence_skips_search_when_route_is_not_rag(decision: str) -> None:
    runtime = AgentRuntime.__new__(AgentRuntime)

    class StubRouter:
        async def route(self, _query):
            return SimpleNamespace(route=decision, graph=GraphPlan())

    runtime.rag_router = StubRouter()
    run = SimpleNamespace(id="run", query="改成表格", notebook_id="notebook", state={})
    state = AgentState(run_id="run", notebook_id="notebook", user_id="user")

    result = await runtime._initial_rag_evidence(None, run, state, None)

    assert result == []


@pytest.mark.asyncio
async def test_approval_resume_does_not_repeat_initial_rag_search() -> None:
    runtime = AgentRuntime.__new__(AgentRuntime)
    run = SimpleNamespace(query="比较论文", state={"pending_batch": {"index": 0}})
    state = AgentState(run_id="run", notebook_id="notebook", user_id="user")

    assert await runtime._initial_rag_evidence(None, run, state, None) == []


@pytest.mark.asyncio
async def test_initial_rag_evidence_keeps_every_retrieved_item() -> None:
    runtime = AgentRuntime.__new__(AgentRuntime)

    class StubRouter:
        async def route(self, _query):
            return SimpleNamespace(route="rag", graph=GraphPlan())

    class StubRetrieval:
        def __init__(self):
            self.calls = []

        async def search(self, db, **kwargs):
            self.calls.append((db, kwargs))
            return {
                "graph_degraded": False,
                "items": [
                    {"chunk_id": f"chunk-{index}", "text": f"论文片段 {index}"}
                    for index in range(8)
                ],
            }

    async def persist(_db, _run, _state, tool_name, observation):
        assert tool_name == "search_notebook"
        for index, item in enumerate(observation["content"]["items"]):
            item["evidence_id"] = f"evidence-{index}"

    runtime.rag_router = StubRouter()
    runtime.retrieval = StubRetrieval()
    runtime._persist_observation_evidence = persist
    db = object()
    run = SimpleNamespace(id="run", query="比较论文", notebook_id="notebook", state={})
    state = AgentState(run_id="run", notebook_id="notebook", user_id="user")

    evidence = await runtime._initial_rag_evidence(db, run, state, ["doc-1"])

    assert len(evidence) == 8
    assert evidence[-1]["evidence_id"] == "evidence-7"
    assert runtime.retrieval.calls == [
        (
            db,
            {
                "notebook_id": "notebook",
                "query": "比较论文",
                "document_ids": ["doc-1"],
                "mode": "auto",
                "top_k": 8,
                "graph_plan": GraphPlan(),
            },
        )
    ]


@pytest.mark.asyncio
async def test_initial_rag_search_failure_leaves_retrieval_to_agent_loop() -> None:
    runtime = AgentRuntime.__new__(AgentRuntime)

    class StubRouter:
        async def route(self, _query):
            return SimpleNamespace(route="rag", graph=GraphPlan())

    class StubRetrieval:
        async def search(self, *_args, **_kwargs):
            raise OSError("retrieval unavailable")

    runtime.rag_router = StubRouter()
    runtime.retrieval = StubRetrieval()
    run = SimpleNamespace(id="run", query="比较论文", notebook_id="notebook", state={})
    state = AgentState(run_id="run", notebook_id="notebook", user_id="user")

    assert await runtime._initial_rag_evidence(None, run, state, None) == []


@pytest.mark.asyncio
async def test_untrimmed_rag_evidence_overflow_fails_run() -> None:
    class FakeDb:
        async def get(self, _model, _key):
            return run

    class FakeEvents:
        def __init__(self):
            self.names = []

        async def emit(self, _db, _run_id, name, _payload):
            self.names.append(name)

        def discard_pending(self):
            return None

    async def empty(*_args):
        return []

    async def no_summary(*_args):
        return None

    async def no_tools(*_args):
        return [], []

    async def huge_rag(*_args):
        return [{"evidence_id": "evidence-1", "text": "中" * 5_000}]

    async def no_commit(_db):
        return None

    runtime = AgentRuntime.__new__(AgentRuntime)
    runtime.settings = Settings(
        model_context_window=3_000,
        max_output_tokens=300,
        context_safety_ratio=0.9,
    )
    runtime.context_builder = ContextBuilder(runtime.settings)
    runtime.registry = SimpleNamespace(schemas=lambda _names: [])
    runtime.events = FakeEvents()
    runtime.skills = SimpleNamespace(summaries=lambda: [])
    runtime._commit = no_commit
    runtime._message_history = empty
    runtime._conversation_summary = no_summary
    runtime._allowed_documents = no_summary
    runtime._attachments = empty
    runtime._initial_tool_context = no_tools
    runtime._initial_rag_evidence = huge_rag
    run = SimpleNamespace(
        id="run",
        conversation_id="conversation",
        notebook_id="notebook",
        user_id="user",
        query="比较论文",
        state={},
        status=RunStatus.pending,
        error_message=None,
    )

    with pytest.raises(ContextOverflowError, match="上下文超限"):
        await runtime.execute(FakeDb(), run.id)

    assert run.status == RunStatus.failed
    assert "run_failed" in runtime.events.names
