import ast
from types import SimpleNamespace

import pytest

from app.agent.context_builder import estimate_tokens
from app.agent.router import DefaultRouterProvider
from app.agent.runtime import ALWAYS_AVAILABLE_TOOLS, AgentRuntime
from app.agent.types import AgentState, ToolSummary
from app.models import RunStatus
from app.tools.native import build_native_registry
from app.tools.registry import ToolDefinition


@pytest.mark.asyncio
async def test_router_selects_relevant_tools_and_caps_count() -> None:
    catalog = [
        ToolSummary(name=f"tool_{index}", description=f"能力 {index}", risk="read")
        for index in range(20)
    ]
    catalog.append(ToolSummary("search_notebook", "检索论文证据", "read"))
    selected = await DefaultRouterProvider().select_tools("请检索论文证据", catalog, 12)
    assert "search_notebook" in selected
    assert len(selected) <= 12


@pytest.mark.asyncio
async def test_context_catalog_matches_initial_callable_tools() -> None:
    registry = build_native_registry()
    for index in range(15):
        registry.register(
            ToolDefinition(
                name=f"mcp.example.tool_{index}",
                description=f"MCP capability {index}",
                arguments_model=None,
                risk="read",
                handler=None,
                source="mcp",
            )
        )

    class StubRouter:
        def __init__(self):
            self.seen = None

        async def select_tools(self, _query, catalog, limit):
            self.seen = (catalog, limit)
            return [item.name for item in catalog[:limit]]

    runtime = AgentRuntime.__new__(AgentRuntime)
    runtime.registry = registry
    runtime.router = StubRouter()
    run = SimpleNamespace(query="查找资料", state={})
    state = AgentState(run_id="run", notebook_id="notebook", user_id="user")

    selected, catalog = await runtime._initial_tool_context(run, state)

    assert selected[:8] == list(ALWAYS_AVAILABLE_TOOLS)
    assert len(selected) == 18
    assert len(selected) == len(set(selected))
    assert runtime.router.seen[1] == 10
    assert all(item.name not in ALWAYS_AVAILABLE_TOOLS for item in runtime.router.seen[0])
    assert selected[8:] == [item.name for item in runtime.router.seen[0][:10]]
    assert [item["name"] for item in catalog] == selected
    assert catalog[0]["source"] == "native"

    run.state["candidate_names"] = list(registry.definitions)
    all_selected, _ = await runtime._initial_tool_context(run, state)
    assert len(all_selected) == len(registry.definitions) > 20


@pytest.mark.asyncio
async def test_context_catalog_adds_loaded_skill_tools_without_filtering_core() -> None:
    runtime = AgentRuntime.__new__(AgentRuntime)
    runtime.registry = build_native_registry()
    run = SimpleNamespace(
        query="继续运行",
        state={"candidate_names": ["task_update", "tool_search"]},
    )
    state = AgentState(
        run_id="run",
        notebook_id="notebook",
        user_id="user",
        active_tool_allowlist=["remove_notebook_source"],
    )

    selected, catalog = await runtime._initial_tool_context(run, state)

    assert selected == [*ALWAYS_AVAILABLE_TOOLS, "remove_notebook_source", "task_update"]
    assert [item["name"] for item in catalog] == selected


@pytest.mark.asyncio
async def test_router_fills_all_remaining_tools_when_fewer_than_ten() -> None:
    runtime = AgentRuntime.__new__(AgentRuntime)
    runtime.registry = build_native_registry()

    class StubRouter:
        async def select_tools(self, _query, catalog, _limit):
            return [catalog[0].name]

    runtime.router = StubRouter()
    run = SimpleNamespace(query="问题", state={})
    state = AgentState(run_id="run", notebook_id="notebook", user_id="user")

    selected, _ = await runtime._initial_tool_context(run, state)

    assert selected[:8] == list(ALWAYS_AVAILABLE_TOOLS)
    assert set(selected) == set(runtime.registry.definitions)


def test_tool_catalog_prompt_updates_without_changing_other_context() -> None:
    messages = [
        {"role": "system", "content": "固定提示词"},
        {
            "role": "system",
            "content": "会话摘要: 旧摘要\n本轮 Evidence: 证据\n可搜索工具目录: [{'name': 'old'}]",
        },
        {"role": "user", "content": "本轮问题"},
    ]

    AgentRuntime._sync_tool_catalog(messages, [{"name": "new"}])

    assert messages[0]["content"] == "固定提示词"
    assert messages[1]["content"] == (
        "会话摘要: 旧摘要\n本轮 Evidence: 证据\n可搜索工具目录: [{'name': 'new'}]"
    )
    assert messages[2]["content"] == "本轮问题"


@pytest.mark.asyncio
async def test_agent_loop_uses_same_tools_in_prompt_and_schemas_after_skill_load() -> None:
    runtime = AgentRuntime.__new__(AgentRuntime)
    runtime.registry = build_native_registry()
    for index in range(15):
        runtime.registry.register(
            ToolDefinition(
                name=f"mcp.example.tool_{index}",
                description=f"MCP capability {index}",
                arguments_model=None,
                risk="read",
                handler=None,
                source="mcp",
            )
        )
    observed = {}

    class StubProvider:
        async def invoke(self, messages, schemas, _on_delta):
            observed["messages"] = messages
            observed["schemas"] = schemas
            return SimpleNamespace(content="完成", tool_calls=[])

    async def tool_context(*_args):
        return SimpleNamespace()

    async def compact(_run, messages, schema_tokens):
        observed["schema_tokens"] = schema_tokens
        return messages, []

    async def no_op(*_args):
        return None

    runtime.provider = StubProvider()
    runtime._tool_context = tool_context
    runtime._compact_working_messages = compact
    runtime._save_transcript = no_op
    runtime._commit = no_op
    state = AgentState(
        run_id="run", notebook_id="notebook", user_id="user",
        active_tool_allowlist=["remove_notebook_source"],
    )
    run = SimpleNamespace(id="run", query="问题", state={}, status=RunStatus.running)
    messages = [
        {"role": "system", "content": "固定提示词"},
        {"role": "system", "content": "会话摘要: {}\n可搜索工具目录: []"},
        {"role": "user", "content": "问题"},
    ]

    answer = await runtime._tool_loop(
        None,
        run,
        state,
        messages,
        None,
        [name for name in runtime.registry.definitions if name != "remove_notebook_source"],
    )

    assert answer == "完成"
    schema_names = [item["function"]["name"] for item in observed["schemas"]]
    prompt_catalog = ast.literal_eval(
        observed["messages"][1]["content"].split("可搜索工具目录: ", 1)[1]
    )
    assert schema_names == [
        runtime.registry.provider_name(item["name"]) for item in prompt_catalog
    ]
    assert len(schema_names) > 20
    assert "remove_notebook_source" in schema_names
    assert observed["schema_tokens"] == estimate_tokens(str(observed["schemas"]))


def test_unknown_and_write_mcp_tools_require_approval() -> None:
    registry = build_native_registry()
    assert registry.requires_approval("missing-tool")
    assert not registry.requires_approval("search_notebook")
    assert len(registry.schemas(list(registry.definitions))) == 11


def test_permission_mode_matrix() -> None:
    registry = build_native_registry()
    assert registry.decide("search_notebook", "read_only") == "allow"
    assert registry.decide("add_paper_to_notebook", "read_only") == "deny"
    assert registry.decide("add_paper_to_notebook", "confirm") == "approve"
    assert registry.decide("add_paper_to_notebook", "auto") == "allow"
    assert registry.decide("remove_notebook_source", "confirm") == "approve"
    assert registry.decide("remove_notebook_source", "auto") == "allow"
    assert registry.decide("remove_notebook_source", "read_only") == "deny"


def test_provider_alias_keeps_dotted_mcp_name_internal() -> None:
    registry = build_native_registry()
    alias = registry.provider_name("mcp.example.search")
    assert alias == "mcp__example__search"
    registry.definitions["mcp.example.search"] = registry.definitions["search_notebook"]
    assert registry.resolve_provider_name(alias) == "mcp.example.search"


@pytest.mark.asyncio
async def test_mcp_arguments_are_validated_before_calling_server() -> None:
    name = "mcp.example.search"
    registry = build_native_registry()
    registry.register(
        ToolDefinition(
            name=name,
            description="Search papers",
            arguments_model=None,
            risk="read",
            handler=None,
            source="mcp",
            raw_schema={
                "type": "object",
                "properties": {"query": {"type": "string"}},
                "required": ["query"],
            },
        )
    )

    class FakeMcp:
        def __init__(self):
            self.calls = []

        async def call_tool(self, tool_name, arguments):
            self.calls.append((tool_name, arguments))
            return {"content": "found"}

    mcp = FakeMcp()
    context = SimpleNamespace(mcp=mcp)
    invalid = await registry.dispatch(name, {"query": 123}, context)
    missing = await registry.dispatch(name, {}, context)
    valid = await registry.dispatch(name, {"query": "climate"}, context)

    assert invalid.ok is False and invalid.error_code == "invalid_arguments"
    assert missing.ok is False and missing.error_code == "invalid_arguments"
    assert valid.ok is True and valid.content == {"content": "found"}
    assert mcp.calls == [(name, {"query": "climate"})]
