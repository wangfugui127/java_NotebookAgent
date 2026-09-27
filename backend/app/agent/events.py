from __future__ import annotations

import json

from redis.asyncio import Redis
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import RunEvent


class EventStore:
    """Durable run events plus an ephemeral realtime channel.

    ``emit`` only writes and stages an event; ``publish_pending`` pushes staged
    durable events to Redis. Callers must commit the transaction in between so a
    rolled-back event is never delivered to the frontend. ``publish`` is for
    transient events (``text_delta``) that are never persisted or replayed.
    """

    def __init__(self, redis_url: str) -> None:
        self.redis = Redis.from_url(redis_url, decode_responses=True)
        self._pending: list[RunEvent] = []

    @staticmethod
    def channel(run_id: str) -> str:
        return f"agent-run:{run_id}"

    async def emit(
        self, db: AsyncSession, run_id: str, event_type: str, payload: dict[str, object]
    ) -> RunEvent:
        current = await db.scalar(
            select(func.max(RunEvent.sequence)).where(RunEvent.run_id == run_id)
        )
        event = RunEvent(
            run_id=run_id,
            sequence=int(current or 0) + 1,
            event_type=event_type,
            payload=payload,
        )
        db.add(event)
        await db.flush()
        self._pending.append(event)
        return event

    async def publish_pending(self) -> None:
        pending = self._pending
        self._pending = []
        for event in pending:
            await self._publish(event.run_id, event.event_type, event.payload, event.sequence)

    def discard_pending(self) -> None:
        self._pending = []

    async def publish(
        self,
        run_id: str,
        event_type: str,
        payload: dict[str, object],
        sequence: int | None = None,
    ) -> None:
        await self._publish(run_id, event_type, payload, sequence)

    async def _publish(
        self,
        run_id: str,
        event_type: str,
        payload: dict[str, object],
        sequence: int | None,
    ) -> None:
        try:
            await self.redis.publish(
                self.channel(run_id),
                json.dumps(
                    {"sequence": sequence, "type": event_type, "payload": payload},
                    ensure_ascii=False,
                    default=str,
                ),
            )
        except Exception:
            # Durable events are recoverable from the database on reconnect.
            pass

    async def close(self) -> None:
        await self.redis.aclose()
