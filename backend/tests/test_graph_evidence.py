from __future__ import annotations

import pytest

from app.retrieval.neo4j_store import Neo4jStore
from app.retrieval.types import GraphEntity


class FakeResult:
    def __aiter__(self):
        async def records():
            for chunk_id in ("chunk-a", "chunk-b"):
                yield {"chunk_id": chunk_id}

        return records()


class FakeSession:
    def __init__(self) -> None:
        self.query = ""
        self.params = {}

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def run(self, query, **params):
        self.query = query
        self.params = params
        return FakeResult()


class FakeDriver:
    def __init__(self) -> None:
        self.current = FakeSession()

    def session(self):
        return self.current


@pytest.mark.asyncio
async def test_graph_evidence_uses_same_experiment_dataset_and_orders_before_limit() -> None:
    store = Neo4jStore.__new__(Neo4jStore)
    store.driver = FakeDriver()

    chunk_ids = await store.search_evidence(
        "notebook",
        [GraphEntity("A", "paper"), GraphEntity("B", "paper"), GraphEntity("X", "dataset")],
        ["dataset", "method", "result"],
        30,
    )

    assert chunk_ids == ["chunk-a", "chunk-b"]
    query = store.driver.current.query
    params = store.driver.current.params
    assert params["notebook_id"] == "notebook"
    assert params["paper_names"] == ["a", "b"]
    assert params["dataset_names"] == ["x"]
    assert params["experiment_types"] == ["USES_DATASET", "USES_METHOD", "REPORTS_RESULT"]
    assert "MATCH (p)-[:HAS_EXPERIMENT]->(e:Experiment)" in query
    assert "OPTIONAL MATCH (e)-[dataset_rel:USES_DATASET]->(required:Dataset)" in query
    assert "WHERE size($dataset_names) = 0 OR dataset_rel IS NOT NULL" in query
    assert "MATCH (e)-[r]->(target:Entity)" in query
    assert "r.document_version_id = v.id" in query
    assert "MATCH (v)-[:HAS_CHUNK]->(evidence:Chunk {id: cid})" in query
    assert "ORDER BY score DESC, path_count DESC, chunk_id ASC" in query
    assert query.index("ORDER BY score DESC") < query.index("LIMIT $limit")


@pytest.mark.asyncio
async def test_graph_evidence_without_entities_does_not_query_graph() -> None:
    store = Neo4jStore.__new__(Neo4jStore)
    store.driver = FakeDriver()

    assert await store.search_evidence("notebook", [], ["method"]) == []
    assert store.driver.current.query == ""
