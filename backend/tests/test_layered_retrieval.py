from __future__ import annotations

from types import SimpleNamespace

import pytest

from app.retrieval.service import RetrievalService
from app.retrieval.types import GraphEntity, GraphPlan, SearchHit


class FakeResult:
    def all(self):
        return []


class FakeDb:
    async def execute(self, _statement):
        return FakeResult()


class FakeMilvus:
    def __init__(self, docs, chunks):
        self.docs = docs
        self.chunks = chunks

    def search_documents(self, query, vector, notebook_id, document_ids, limit):
        return list(self.docs)

    def hybrid_search(self, query, vector, notebook_id, document_ids, section_ids, limit):
        return list(self.chunks)


class FakeNeo4j:
    async def verify(self):
        return False


class EmptyNeo4j:
    def __init__(self) -> None:
        self.calls = []

    async def verify(self):
        return True

    async def search_evidence(self, notebook_id, entities, intents, limit):
        self.calls.append((notebook_id, entities, intents, limit))
        return []


class FailingNeo4j(EmptyNeo4j):
    async def search_evidence(self, notebook_id, entities, intents, limit):
        raise OSError("graph unavailable")


class FakeSiliconFlow:
    async def embed(self, texts):
        return [[0.1, 0.2, 0.3] for _ in texts]

    async def rerank(self, query, documents, top_n):
        return [
            {"index": index, "relevance_score": 1.0 - index * 0.1}
            for index in range(min(top_n, len(documents)))
        ]


class FakeGraphRouter:
    def __init__(self, plan: GraphPlan | None = None) -> None:
        self.plan = plan or GraphPlan()
        self.calls: list[str] = []

    async def route(self, query: str):
        self.calls.append(query)
        return SimpleNamespace(graph=self.plan)


def hit(chunk_id: str) -> SearchHit:
    return SearchHit(
        chunk_id=chunk_id,
        text=f"content {chunk_id}",
        score=1.0,
        document_id="doc-1",
        document_version_id="version-1",
        title="Paper",
        section_id="sec-1",
    )


class RecordingMilvus(FakeMilvus):
    def __init__(self) -> None:
        super().__init__([{"entity": {"document_id": "doc-1"}}], [hit("c1")])
        self.calls: list[list[str] | None] = []

    def hybrid_search(self, query, vector, notebook_id, document_ids, section_ids, limit):
        self.calls.append(document_ids)
        return list(self.chunks)


def service(docs, chunks) -> RetrievalService:
    return RetrievalService(
        FakeMilvus(docs, chunks), FakeNeo4j(), FakeSiliconFlow(), FakeGraphRouter()
    )


@pytest.mark.asyncio
async def test_layered_mode_uses_document_layer() -> None:
    docs = [{"entity": {"document_id": "doc-1"}}]
    result = await service(docs, [hit("c1"), hit("c2")]).search(
        FakeDb(), notebook_id="nb", query="q", document_ids=None, mode="layered"
    )
    assert result["layered_used"] is True
    assert result["mode_used"] == "layered"
    assert [item["chunk_id"] for item in result["items"]]


@pytest.mark.asyncio
async def test_layered_degrades_when_no_document_profiles() -> None:
    result = await service([], [hit("c1")]).search(
        FakeDb(), notebook_id="nb", query="q", document_ids=None, mode="layered"
    )
    assert result["layered_used"] is False
    assert result["layered_degraded"] is True
    assert result["mode_used"] == "hybrid"


@pytest.mark.asyncio
async def test_auto_uses_layered_when_profiles_exist() -> None:
    docs = [{"entity": {"document_id": "doc-1"}}]
    result = await service(docs, [hit("c1")]).search(
        FakeDb(), notebook_id="nb", query="帮我总结", document_ids=None, mode="auto"
    )
    assert result["layered_used"] is True


@pytest.mark.asyncio
async def test_layered_still_searches_full_notebook_scope() -> None:
    milvus = RecordingMilvus()
    retrieval = RetrievalService(milvus, FakeNeo4j(), FakeSiliconFlow(), FakeGraphRouter())
    result = await retrieval.search(
        FakeDb(),
        notebook_id="nb",
        query="q",
        document_ids=["d1", "d2"],
        mode="layered",
    )
    assert result["fallback_full_notebook"] is True
    assert ["d1", "d2"] in milvus.calls
    assert all(call is not None for call in milvus.calls)


@pytest.mark.asyncio
async def test_graph_empty_still_uses_both_milvus_results() -> None:
    milvus = RecordingMilvus()
    neo4j = EmptyNeo4j()
    graph_plan = GraphPlan(True, (GraphEntity("X", "dataset"),), ("method", "result"))

    router = FakeGraphRouter()
    result = await RetrievalService(milvus, neo4j, FakeSiliconFlow(), router).search(
        FakeDb(), notebook_id="nb", query="X 上的方法和结果", document_ids=None,
        mode="auto", graph_plan=graph_plan,
    )

    assert result["graph_attempted"] is True
    assert result["graph_used"] is False
    assert [item["chunk_id"] for item in result["items"]] == ["c1"]
    assert len(milvus.calls) == 2
    assert neo4j.calls == [("nb", [GraphEntity("X", "dataset")], ["method", "result"], 30)]
    assert router.calls == []


@pytest.mark.asyncio
async def test_explicit_no_graph_plan_skips_second_llm_call() -> None:
    milvus = RecordingMilvus()
    neo4j = EmptyNeo4j()
    router = FakeGraphRouter()

    result = await RetrievalService(milvus, neo4j, FakeSiliconFlow(), router).search(
        FakeDb(), notebook_id="nb", query="比较方法和结果", document_ids=None,
        mode="auto", graph_plan=GraphPlan(),
    )

    assert result["graph_attempted"] is False
    assert result["graph_used"] is False
    assert neo4j.calls == []
    assert len(milvus.calls) == 2
    assert router.calls == []


@pytest.mark.asyncio
async def test_missing_plan_uses_llm_graph_decision_not_keywords_or_mode() -> None:
    milvus = RecordingMilvus()
    neo4j = EmptyNeo4j()
    router = FakeGraphRouter(GraphPlan(True, (GraphEntity("X", "dataset"),), ("method",)))

    result = await RetrievalService(milvus, neo4j, FakeSiliconFlow(), router).search(
        FakeDb(), notebook_id="nb", query="X", document_ids=None, mode="hybrid",
    )

    assert router.calls == ["X"]
    assert result["graph_attempted"] is True
    assert neo4j.calls == [("nb", [GraphEntity("X", "dataset")], ["method"], 30)]


@pytest.mark.asyncio
async def test_llm_can_skip_graph_even_in_hybrid_graph_mode() -> None:
    milvus = RecordingMilvus()
    neo4j = EmptyNeo4j()
    router = FakeGraphRouter()

    result = await RetrievalService(milvus, neo4j, FakeSiliconFlow(), router).search(
        FakeDb(), notebook_id="nb", query="比较方法和结果", document_ids=None,
        mode="hybrid_graph",
    )

    assert router.calls == ["比较方法和结果"]
    assert result["graph_attempted"] is False
    assert neo4j.calls == []


@pytest.mark.asyncio
async def test_graph_failure_keeps_milvus_results() -> None:
    milvus = RecordingMilvus()
    result = await RetrievalService(
        milvus, FailingNeo4j(), FakeSiliconFlow(), FakeGraphRouter()
    ).search(
        FakeDb(), notebook_id="nb", query="X 上的方法", document_ids=None,
        mode="auto", graph_plan=GraphPlan(True, (GraphEntity("X", "dataset"),), ("method",)),
    )

    assert result["graph_degraded"] is True
    assert [item["chunk_id"] for item in result["items"]] == ["c1"]
    assert len(milvus.calls) == 2
