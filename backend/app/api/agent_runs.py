from __future__ import annotations

import asyncio
import json
import time

from fastapi import APIRouter, HTTPException, Request
from redis.asyncio import Redis
from sqlalchemy import select
from sse_starlette.sse import EventSourceResponse

from app.agent.events import EventStore
from app.agent.run_service import create_agent_run
from app.agent.tasks import execute_agent_run_task
from app.api.dependencies import CurrentUser, DbSession
from app.core.config import get_settings
from app.core.database import SessionFactory
from app.models import AgentRun, ApprovalRequest, Conversation, RunEvent, RunStatus
from app.schemas import AgentRunCreate, AgentRunView, ApprovalResolve

router = APIRouter(tags=["agent"])

TERMINAL_EVENTS = {"run_completed", "run_failed", "run_cancelled"}


def _sse_event(event: RunEvent) -> dict[str, str]:
    return {
        "id": str(event.sequence),
        "event": event.event_type,
        "data": json.dumps(event.payload, ensure_ascii=False, default=str),
    }


async def _open_pubsub(redis_url: str, channel: str) -> tuple[Redis | None, object | None]:
    """Best-effort live channel; returns (None, None) when Redis is unavailable."""
    try:
        redis = Redis.from_url(redis_url, decode_responses=True)
        pubsub = redis.pubsub()
        await pubsub.subscribe(channel)
    except Exception:
        return None, None
    return redis, pubsub


async def owned_run(db: DbSession, run_id: str, user_id: str) -> AgentRun:
    run = await db.scalar(
        select(AgentRun).where(AgentRun.id == run_id, AgentRun.user_id == user_id)
    )
    if not run:
        raise HTTPException(404, "run not found")
    return run


@router.post("/agent/runs", response_model=AgentRunView, status_code=202)
async def create_run(payload: AgentRunCreate, db: DbSession, user: CurrentUser) -> AgentRun:
    conversation = await db.scalar(
        select(Conversation).where(
            Conversation.id == payload.conversation_id, Conversation.user_id == user.id
        )
    )
    if not conversation:
        raise HTTPException(404, "conversation not found")
    run = await create_agent_run(
        db,
        user=user,
        conversation=conversation,
        query=payload.query,
        attachment_ids=payload.attachment_ids,
        attachment_instructions=payload.attachment_instructions,
        approval_mode=payload.approval_mode,
    )
    execute_agent_run_task.delay(run.id)
    return run


@router.get("/agent/runs/{run_id}", response_model=AgentRunView)
async def get_run(run_id: str, db: DbSession, user: CurrentUser) -> AgentRun:
    return await owned_run(db, run_id, user.id)


@router.get("/agent/runs/{run_id}/context-manifest")
async def get_context_manifest(run_id: str, db: DbSession, user: CurrentUser) -> dict[str, object]:
    run = await owned_run(db, run_id, user.id)
    return {"items": run.state.get("context_manifests", [])}


@router.get("/agent/runs/{run_id}/events")
async def stream_events(
    run_id: str, request: Request, db: DbSession, user: CurrentUser
) -> EventSourceResponse:
    await owned_run(db, run_id, user.id)
    last_event_id = int(
        request.headers.get("last-event-id") or request.query_params.get("after") or 0
    )

    async def replay(session, after: int) -> list[RunEvent]:
        return list(
            (
                await session.execute(
                    select(RunEvent)
                    .where(RunEvent.run_id == run_id, RunEvent.sequence > after)
                    .order_by(RunEvent.sequence)
                )
            )
            .scalars()
            .all()
        )

    async def generator():
        cursor = last_event_id
        settings = get_settings()
        redis, pubsub = await _open_pubsub(settings.redis_url, EventStore.channel(run_id))
        last_activity = time.monotonic()
        try:
            # 1) Replay committed durable events (fills gaps from a lost publish).
            async with SessionFactory() as session:
                for event in await replay(session, cursor):
                    cursor = event.sequence
                    yield _sse_event(event)
                    if event.event_type in TERMINAL_EVENTS:
                        return
            # 2) Forward the live channel. Only query the database after a
            #    silence period (or while Redis is down), never per delta.
            while True:
                if await request.is_disconnected():
                    return
                message = None
                if pubsub is not None:
                    try:
                        message = await pubsub.get_message(
                            ignore_subscribe_messages=True, timeout=0.5
                        )
                    except Exception:
                        pubsub = None
                if message and message.get("type") == "message":
                    last_activity = time.monotonic()
                    data = json.loads(message["data"])
                    sequence = data.get("sequence")
                    if sequence is None:
                        yield {
                            "event": data["type"],
                            "data": json.dumps(
                                data["payload"], ensure_ascii=False, default=str
                            ),
                        }
                    elif int(sequence) > cursor:
                        cursor = int(sequence)
                        yield {
                            "id": str(sequence),
                            "event": data["type"],
                            "data": json.dumps(
                                data["payload"], ensure_ascii=False, default=str
                            ),
                        }
                        if data["type"] in TERMINAL_EVENTS:
                            return
                    continue
                if pubsub is not None and time.monotonic() - last_activity < 2.0:
                    continue
                last_activity = time.monotonic()
                status: RunStatus | None = None
                events: list[RunEvent] = []
                async with SessionFactory() as session:
                    events = await replay(session, cursor)
                    for event in events:
                        cursor = event.sequence
                        yield _sse_event(event)
                        if event.event_type in TERMINAL_EVENTS:
                            return
                    run = await session.get(AgentRun, run_id)
                    status = run.status if run else None
                if status in {RunStatus.completed, RunStatus.failed, RunStatus.cancelled}:
                    return
                if pubsub is None:
                    await asyncio.sleep(0.5)
        finally:
            if pubsub is not None:
                try:
                    await pubsub.unsubscribe(EventStore.channel(run_id))
                except Exception:
                    pass
                await pubsub.aclose()
            if redis is not None:
                await redis.aclose()

    return EventSourceResponse(generator())


@router.post("/agent/runs/{run_id}/cancel", status_code=202)
async def cancel_run(run_id: str, db: DbSession, user: CurrentUser) -> dict[str, str]:
    run = await owned_run(db, run_id, user.id)
    run.status = RunStatus.cancelled
    await db.commit()
    return {"status": "cancelled"}


@router.post("/agent/runs/{run_id}/retry", status_code=202)
async def retry_run(run_id: str, db: DbSession, user: CurrentUser) -> dict[str, str]:
    run = await owned_run(db, run_id, user.id)
    if run.status not in {RunStatus.failed, RunStatus.cancelled}:
        raise HTTPException(409, "only failed or cancelled runs can be retried")
    run.status = RunStatus.pending
    run.error_message = None
    await db.commit()
    execute_agent_run_task.delay(run.id)
    return {"status": "pending"}


@router.get("/agent/runs/{run_id}/approvals")
async def list_approvals(run_id: str, db: DbSession, user: CurrentUser) -> dict[str, object]:
    await owned_run(db, run_id, user.id)
    approvals = (
        (await db.execute(select(ApprovalRequest).where(ApprovalRequest.run_id == run_id)))
        .scalars()
        .all()
    )
    return {
        "items": [
            {
                "id": item.id,
                "status": item.status,
                "reason": item.reason,
                "mode": item.mode,
                "tool_risk": item.tool_risk,
                "tool_call_id": item.tool_call_id,
            }
            for item in approvals
        ]
    }


@router.post("/agent/approvals/{approval_id}/resolve", status_code=202)
async def resolve_approval(
    approval_id: str, payload: ApprovalResolve, db: DbSession, user: CurrentUser
) -> dict[str, str]:
    approval = await db.scalar(
        select(ApprovalRequest)
        .join(AgentRun, ApprovalRequest.run_id == AgentRun.id)
        .where(ApprovalRequest.id == approval_id, AgentRun.user_id == user.id)
    )
    if not approval:
        raise HTTPException(404, "approval not found")
    if approval.status != "pending":
        raise HTTPException(409, "approval already resolved")
    approval.status = payload.decision
    approval.resolved_by = user.id
    await db.commit()
    execute_agent_run_task.delay(approval.run_id)
    return {"status": payload.decision}
