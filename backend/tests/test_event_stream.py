from __future__ import annotations

import pytest

from app.api import agent_runs


@pytest.mark.asyncio
async def test_open_pubsub_returns_none_when_redis_unavailable(monkeypatch) -> None:
    def boom(*_args, **_kwargs):
        raise RuntimeError("redis down")

    monkeypatch.setattr(agent_runs.Redis, "from_url", boom)
    redis, pubsub = await agent_runs._open_pubsub("redis://unavailable:6379/0", "agent-run:x")
    assert redis is None
    assert pubsub is None
