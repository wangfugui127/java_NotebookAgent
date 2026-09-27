from __future__ import annotations

from types import SimpleNamespace

import pytest

from app.ingestion.tasks import extract_section_batches


@pytest.mark.asyncio
async def test_extract_section_batches_covers_every_chunk() -> None:
    chunks = [SimpleNamespace(content=f"chunk-{index}") for index in range(20)]
    extract_titles: list[str] = []
    batch_sizes: list[int] = []
    upserted: list[str] = []

    async def extract(title, segments):
        extract_titles.append(title)
        batch_sizes.append(len(segments))
        return [([], []) for _ in segments]

    async def upsert(chunk, entities, relations):
        upserted.append(chunk.content)

    calls = await extract_section_batches(
        "S3. Evaluation datasets", chunks, extract=extract, upsert=upsert
    )

    assert calls == 3
    assert len(extract_titles) == 3
    assert batch_sizes == [8, 8, 4]
    assert upserted == [f"chunk-{index}" for index in range(20)]
