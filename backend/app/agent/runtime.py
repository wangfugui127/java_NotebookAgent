from __future__ import annotations

import json
import logging
import re
from dataclasses import asdict
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.agent.compactor import ContextCompactor, ContextOverflowError
from app.agent.context_builder import ContextBuilder, estimate_tokens, messages_tokens
from app.agent.events import EventStore
from app.agent.messages import sanitize_messages
from app.agent.messages import turns as split_turns
from app.agent.rag_router import LLMRagRouter
from app.agent.router import EmbeddingRouterProvider, RouterProvider
from app.agent.types import AgentState, AttachmentPayload, QueryEnvelope
from app.core.config import Settings
from app.models import (
    AgentRun,
    ApprovalRequest,
    Checkpoint,
    Chunk,
    Citation,
    ConversationResourceSelection,
    ConversationSummary,
    Document,
    DocumentVersion,
    Evidence,
    Message,
    RunStatus,
    RunTranscript,
    Section,
    ToolCall,
)
from app.prompts.agent import (
    WINDOW_REDUCER_SYSTEM_PROMPT,
    aggregate_windows_prompt,
    evidence_map_prompt,
    window_reduce_prompt,
)
from app.providers.deepseek import DeepSeekProvider
from app.providers.openalex import OpenAlexProvider
from app.providers.siliconflow import SiliconFlowProvider
from app.retrieval.milvus import MilvusStore
from app.retrieval.neo4j_store import Neo4jStore
from app.retrieval.service import RetrievalService
from app.skills.catalog import SkillCatalog
from app.tools.native import build_native_registry
from app.tools.registry import ToolExecutionContext, ToolRegistry

logger = logging.getLogger(__name__)

ALWAYS_AVAILABLE_TOOLS = (
    "load_skill",
    "tool_search",
    "read_mcp_resource",
    "list_notebook_sources",
    "search_notebook",
    "get_notebook_items",
    "search_papers",
    "get_paper_details",
)
ROUTED_TOOL_COUNT = 10


class AgentRuntime:
    def __init__(
        self,
        settings: Settings,
        mcp: Any,
        *,
        router: RouterProvider | None = None,
        registry: ToolRegistry | None = None,
    ) -> None:
        self.settings = settings
        self.provider = DeepSeekProvider(settings)
        self.rag_router = LLMRagRouter(self.provider)
        self.openalex = OpenAlexProvider(settings)
        siliconflow = SiliconFlowProvider(settings)
        self.retrieval = RetrievalService(MilvusStore(settings), Neo4jStore(settings), siliconflow)
        self.mcp = mcp
        self.registry = registry or build_native_registry()
        self.registry.register_mcp(list(mcp.tools.values()))
        self.router = router or EmbeddingRouterProvider(siliconflow)
        self.skills = SkillCatalog(settings.skills_config)
        self.skills.reload(set(self.registry.definitions))
        self.context_builder = ContextBuilder(settings)
        self.compactor = ContextCompactor(settings, self.provider)
        self.events = EventStore(settings.redis_url)

    async def close(self) -> None:
        await self.provider.close()
        await self.openalex.close()
        await self.retrieval.close()
        await self.events.close()

    async def _message_history(
        self,
        db: AsyncSession,
        conversation_id: str,
        exclude_message_id: str | None = None,
    ) -> list[dict[str, Any]]:
        """Full conversation history from the messages table (authoritative)."""
        messages = (
            (
                await db.execute(
                    select(Message)
                    .where(Message.conversation_id == conversation_id)
                    .order_by(Message.created_at.asc(), Message.id.asc())
                )
            )
            .scalars()
            .all()
        )
        return [
            {"role": item.role, "content": item.content, "message_id": item.id}
            for item in messages
            if item.role in {"user", "assistant"} and item.id != exclude_message_id
        ]

    async def _save_transcript(
        self,
        db: AsyncSession,
        run: AgentRun,
        messages: list[dict[str, Any]],
        answer: str | None = None,
    ) -> None:
        cleaned = sanitize_messages(
            [item for item in messages if item.get("role") in {"user", "assistant", "tool"}]
        )
        stored = [
            {
                key: item[key]
                for key in ("role", "content", "tool_calls", "tool_call_id")
                if key in item
            }
            for item in cleaned
        ]
        if answer is not None:
            stored.append({"role": "assistant", "content": answer})
        existing = await db.get(RunTranscript, run.id)
        if existing:
            existing.messages = stored
        else:
            db.add(
                RunTranscript(
                    run_id=run.id,
                    conversation_id=run.conversation_id,
                    messages=stored,
                )
            )
        await db.flush()

    async def _conversation_summary(
        self, db: AsyncSession, conversation_id: str
    ) -> dict[str, Any] | None:
        row = await db.scalar(
            select(ConversationSummary)
            .where(ConversationSummary.conversation_id == conversation_id)
            .order_by(ConversationSummary.created_at.desc())
            .limit(1)
        )
        return dict(row.summary) if row else None

    async def _persist_summary(
        self, db: AsyncSession, conversation_id: str, summary: dict[str, Any] | None
    ) -> None:
        if not summary:
            return
        through_message_id = str(summary.get("through_message_id") or "")
        if not through_message_id:
            return
        existing = await db.scalar(
            select(ConversationSummary).where(
                ConversationSummary.conversation_id == conversation_id,
                ConversationSummary.through_message_id == through_message_id,
            )
        )
        if not existing:
            db.add(
                ConversationSummary(
                    conversation_id=conversation_id,
                    through_message_id=through_message_id,
                    summary=summary,
                )
            )
            await db.flush()

    async def _allowed_documents(self, db: AsyncSession, run: AgentRun) -> list[str] | None:
        rows = (
            (
                await db.execute(
                    select(ConversationResourceSelection).where(
                        ConversationResourceSelection.conversation_id == run.conversation_id,
                        ConversationResourceSelection.mode != "off",
                    )
                )
            )
            .scalars()
            .all()
        )
        return [row.document_id for row in rows] if rows else None

    async def _attachments(self, db: AsyncSession, run: AgentRun) -> list[AttachmentPayload]:
        if not run.attachment_ids:
            return []
        rows = (
            await db.execute(
                select(Document, DocumentVersion)
                .join(DocumentVersion, Document.active_version_id == DocumentVersion.id)
                .where(
                    Document.id.in_(run.attachment_ids),
                    Document.notebook_id == run.notebook_id,
                )
            )
        ).all()
        instructions = run.state.get("attachment_instructions", {})
        payloads: list[AttachmentPayload] = []
        for document, version in rows:
            if not version.full_text:
                raise ValueError(f"attachment is not ready: {document.title}")
            chunks = (
                (
                    await db.execute(
                        select(Chunk)
                        .where(Chunk.document_version_id == version.id, Chunk.is_active.is_(True))
                        .order_by(Chunk.ordinal)
                    )
                )
                .scalars()
                .all()
            )
            payloads.append(
                AttachmentPayload(
                    document_id=document.id,
                    document_version_id=version.id,
                    name=document.title,
                    media_type=document.media_type,
                    instruction=str(instructions.get(document.id) or ""),
                    text=version.full_text,
                    page_ranges=[
                        {
                            "char_start": chunk.char_start,
                            "char_end": chunk.char_end,
                            "page_start": chunk.page_start,
                            "page_end": chunk.page_end,
                        }
                        for chunk in chunks
                    ],
                )
            )
        found = {item.document_id for item in payloads}
        missing = set(run.attachment_ids) - found
        if missing:
            raise PermissionError(f"attachments not found in current notebook: {sorted(missing)}")
        return payloads

    async def _checkpoint(
        self, db: AsyncSession, run: AgentRun, boundary: str, state: AgentState
    ) -> None:
        db.add(Checkpoint(run_id=run.id, boundary=boundary, state=asdict(state)))
        run.state = run.state | {"agent_state": asdict(state)}
        await db.flush()

    async def _commit(self, db: AsyncSession) -> None:
        """Commit, then publish staged durable events so nothing uncommitted leaks."""
        try:
            await db.commit()
        except Exception:
            self.events.discard_pending()
            raise
        await self.events.publish_pending()

    @staticmethod
    def _apply_summary_boundary(
        history: list[dict[str, Any]],
        stored_summary: dict[str, Any] | None,
        *,
        keep_recent_turns: int = 0,
    ) -> tuple[str, list[dict[str, Any]]]:
        """Only inject a stored summary when its message boundary is present.

        Otherwise the raw history already contains that text and adding the
        summary again would duplicate it.
        """
        if not stored_summary:
            return "", history
        boundary = str(stored_summary.get("through_message_id") or "")
        text = str(stored_summary.get("text") or "").strip()
        if not boundary or not text:
            return "", history
        index = next(
            (i for i, item in enumerate(history) if item.get("message_id") == boundary),
            None,
        )
        if index is None:
            return "", history
        remaining = history[index + 1 :]
        if keep_recent_turns and (
            len(split_turns(remaining)) < keep_recent_turns
            or not remaining
            or remaining[0].get("role") != "user"
        ):
            return "", history
        return text, remaining

    def _plan_history(
        self,
        full_history: list[dict[str, Any]],
        probe_text: str,
        probe_remaining: list[dict[str, Any]],
        budget: int,
        reserve: int,
        fixed_without: int,
        fixed_with: int,
    ) -> tuple[list[dict[str, Any]], str, int | None]:
        """Budget-first history decision using the exact build_packets measurement.

        Returns ``(history, summary_text, compact_budget)``; ``compact_budget``
        is None when no further compaction is needed.
        """
        available_without = budget - reserve - fixed_without
        if not full_history:
            return full_history, "", None
        if messages_tokens(full_history) <= available_without:
            return full_history, "", None
        if available_without <= 0:
            raise ContextOverflowError(
                "fixed context leaves no room for history: "
                f"available={available_without}, history={messages_tokens(full_history)}"
            )
        avail_with = budget - reserve - fixed_with
        if probe_text and avail_with > 0:
            if messages_tokens(probe_remaining) <= avail_with:
                return probe_remaining, probe_text, None
            if probe_remaining:
                return probe_remaining, probe_text, avail_with
        # A stored summary that consumes the available space cannot help. Build
        # a fresh, budgeted summary from the authoritative full message history.
        return full_history, "", available_without

    async def _tool_context(
        self, db: AsyncSession, state: AgentState, allowed_documents: list[str] | None
    ) -> ToolExecutionContext:
        return ToolExecutionContext(
            db=db,
            settings=self.settings,
            state=state,
            retrieval=self.retrieval,
            openalex=self.openalex,
            skills=self.skills,
            mcp=self.mcp,
            registry=self.registry,
            allowed_document_ids=allowed_documents,
        )

    async def _initial_tool_context(
        self, run: AgentRun, state: AgentState
    ) -> tuple[list[str], list[dict[str, Any]]]:
        """Expose core and loaded-Skill tools, plus ten distinct routed tools."""
        selected = [
            name
            for name in dict.fromkeys(
                [*ALWAYS_AVAILABLE_TOOLS, *(state.active_tool_allowlist or [])]
            )
            if name in self.registry.definitions
        ]
        candidate_names = run.state.get("candidate_names")
        if candidate_names is None:
            selected_set = set(selected)
            remaining = [
                item for item in self.registry.summaries() if item.name not in selected_set
            ]
            ranked = (
                await self.router.select_tools(run.query, remaining, ROUTED_TOOL_COUNT)
                if remaining
                else []
            )
            remaining_names = [item.name for item in remaining]
            remaining_set = set(remaining_names)
            candidate_names = list(
                dict.fromkeys(name for name in ranked if name in remaining_set)
            )
            candidate_names.extend(
                name for name in remaining_names if name not in candidate_names
            )
            candidate_names = candidate_names[: min(ROUTED_TOOL_COUNT, len(remaining))]
        selected = [
            name
            for name in dict.fromkeys([*selected, *candidate_names])
            if name in self.registry.definitions
        ]
        catalog = [
            asdict(self.registry.definitions[name].summary()) for name in selected
        ]
        return selected, catalog

    @staticmethod
    def _sync_tool_catalog(
        messages: list[dict[str, Any]], catalog: list[dict[str, Any]]
    ) -> None:
        marker = "\n可搜索工具目录: "
        for message in messages:
            content = message.get("content")
            if (
                message.get("role") == "system"
                and isinstance(content, str)
                and content.startswith("会话摘要:")
                and marker in content
            ):
                message["content"] = f"{content.rsplit(marker, 1)[0]}{marker}{catalog}"
                return

    async def _initial_rag_evidence(
        self,
        db: AsyncSession,
        run: AgentRun,
        state: AgentState,
        allowed_documents: list[str] | None,
    ) -> list[dict[str, Any]]:
        if run.state.get("pending_batch"):
            return []
        if await self.rag_router.route(run.query) != "rag":
            return []
        try:
            result = await self.retrieval.search(
                db,
                notebook_id=run.notebook_id,
                query=run.query,
                document_ids=allowed_documents,
                mode="auto",
                top_k=8,
            )
        except Exception:
            logger.exception("Initial Notebook RAG search failed for run %s", run.id)
            return []
        state.graph_degraded = bool(result.get("graph_degraded"))
        observation = {"ok": True, "content": result}
        await self._persist_observation_evidence(
            db, run, state, "search_notebook", observation
        )
        return list(result.get("items") or [])

    async def _compact_working_messages(
        self, run: AgentRun, messages: list[dict[str, Any]], schema_tokens: int = 0
    ) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
        budget = self.settings.effective_input_budget - schema_tokens
        if budget <= 0:
            raise ContextOverflowError("上下文超限：工具 schema 已占满输入预算")
        over_budget = messages_tokens(messages) > budget
        try:
            compacted, omissions, _ = await self.compactor.compact(
                messages,
                tool_cleanup_done=bool(run.state.get("tool_cleanup_done")),
                budget=budget,
            )
        except ContextOverflowError as exc:
            if schema_tokens:
                raise ContextOverflowError(f"上下文超限：{exc}") from exc
            raise
        if over_budget and not run.state.get("tool_cleanup_done"):
            run.state = run.state | {"tool_cleanup_done": True}
        return compacted, omissions

    async def _persist_observation_evidence(
        self,
        db: AsyncSession,
        run: AgentRun,
        state: AgentState,
        tool_name: str,
        observation: dict[str, Any],
    ) -> None:
        if not observation.get("ok") or tool_name not in {
            "search_notebook",
            "get_notebook_items",
        }:
            return
        content = observation.get("content") or {}
        for item in content.get("items") or []:
            chunk_id = item.get("chunk_id")
            if not chunk_id:
                continue
            evidence = Evidence(
                run_id=run.id,
                chunk_id=chunk_id,
                source_kind="notebook_chunk",
                source_id=str(item.get("document_version_id") or chunk_id),
                text=str(item.get("text") or item.get("content") or ""),
                section_title=item.get("section_title"),
                page_start=item.get("page_start"),
                page_end=item.get("page_end"),
                char_start=item.get("char_start"),
                char_end=item.get("char_end"),
                block_ids=list(item.get("block_ids") or []),
                retrieval_sources=list(item.get("sources") or [tool_name]),
                score=item.get("score"),
            )
            db.add(evidence)
            await db.flush()
            item["evidence_id"] = evidence.id
            state.evidence_ids.append(evidence.id)

    async def _attach_packet_evidence(
        self,
        db: AsyncSession,
        run: AgentRun,
        packets: list[Any],
        attachments: list[AttachmentPayload],
        state: AgentState,
    ) -> None:
        version_ids = [item.document_version_id for item in attachments]
        chunk_rows: list[Chunk] = []
        if version_ids:
            chunk_rows = list(
                (
                    await db.execute(
                        select(Chunk)
                        .where(
                            Chunk.document_version_id.in_(version_ids),
                            Chunk.is_active.is_(True),
                        )
                        .order_by(Chunk.document_version_id, Chunk.ordinal)
                    )
                )
                .scalars()
                .all()
            )
        chunks_by_version: dict[str, list[Chunk]] = {}
        section_ids = {chunk.section_id for chunk in chunk_rows if chunk.section_id}
        section_titles: dict[str, str] = {}
        if section_ids:
            section_titles = {
                row[0]: row[1]
                for row in (
                    await db.execute(
                        select(Section.id, Section.title).where(Section.id.in_(section_ids))
                    )
                ).all()
            }
        for chunk in chunk_rows:
            chunks_by_version.setdefault(chunk.document_version_id, []).append(chunk)
        evidence_by_chunk: dict[str, Evidence] = {}

        async def descriptor_for_chunk(chunk: Chunk) -> dict[str, Any]:
            section_title = section_titles.get(chunk.section_id or "")
            evidence = evidence_by_chunk.get(chunk.id)
            if evidence is None:
                evidence = Evidence(
                    run_id=run.id,
                    chunk_id=chunk.id,
                    source_kind="attachment_chunk",
                    source_id=chunk.document_version_id,
                    text=chunk.content,
                    section_title=section_title,
                    page_start=chunk.page_start,
                    page_end=chunk.page_end,
                    char_start=chunk.char_start,
                    char_end=chunk.char_end,
                    block_ids=list(chunk.block_ids or []),
                    retrieval_sources=["current_query_attachment"],
                )
                db.add(evidence)
                await db.flush()
                evidence_by_chunk[chunk.id] = evidence
                state.evidence_ids.append(evidence.id)
            return {
                "evidence_id": evidence.id,
                "chunk_id": chunk.id,
                "document_version_id": chunk.document_version_id,
                "section_title": section_title,
                "page_start": chunk.page_start,
                "page_end": chunk.page_end,
                "char_start": chunk.char_start,
                "char_end": chunk.char_end,
                "preview": chunk.content[:160],
            }

        if len(packets) == 1 and attachments:
            descriptors: list[dict[str, Any]] = []
            by_version: dict[str, list[dict[str, Any]]] = {}
            for attachment in attachments:
                for chunk in chunks_by_version.get(attachment.document_version_id, []):
                    descriptor = await descriptor_for_chunk(chunk)
                    descriptors.append(descriptor)
                    by_version.setdefault(attachment.document_version_id, []).append(descriptor)
            for manifest_entry in packets[0].manifest.attachments:
                manifest_entry["evidence"] = by_version.get(
                    str(manifest_entry.get("document_version_id")), []
                )
            if descriptors:
                packets[0].messages.insert(
                    -1, {"role": "system", "content": evidence_map_prompt(descriptors)}
                )
            return

        for packet in packets:
            window = packet.attachment_window
            if not window:
                continue
            matching = [
                chunk
                for chunk in chunks_by_version.get(window.document_version_id, [])
                if chunk.char_end > window.char_start and chunk.char_start < window.char_end
            ]
            descriptors = [await descriptor_for_chunk(chunk) for chunk in matching]
            if not descriptors:
                evidence = Evidence(
                    run_id=run.id,
                    source_kind="query_window",
                    source_id=window.document_version_id,
                    text=window.text,
                    page_start=window.page_start,
                    page_end=window.page_end,
                    char_start=window.char_start,
                    char_end=window.char_end,
                    retrieval_sources=["current_query_window"],
                )
                db.add(evidence)
                await db.flush()
                state.evidence_ids.append(evidence.id)
                descriptors = [
                    {
                        "evidence_id": evidence.id,
                        "chunk_id": None,
                        "document_version_id": window.document_version_id,
                        "page_start": window.page_start,
                        "page_end": window.page_end,
                        "char_start": window.char_start,
                        "char_end": window.char_end,
                    }
                ]
            packet.manifest.attachments[0]["evidence"] = descriptors
            packet.messages.insert(
                -1, {"role": "system", "content": evidence_map_prompt(descriptors)}
            )

    async def _finish_batch_call(
        self,
        db: AsyncSession,
        run: AgentRun,
        state: AgentState,
        messages: list[dict[str, Any]],
        entry: dict[str, Any],
        observation: dict[str, Any],
        candidate_names: list[str],
    ) -> None:
        record = await db.get(ToolCall, entry["tool_call_id"])
        if record:
            record.status = "completed" if observation.get("ok") else "failed"
            if observation.get("error_code") in {"approval_rejected", "permission_denied"}:
                record.status = "rejected"
            record.result = observation
        await self._persist_observation_evidence(db, run, state, entry["name"], observation)
        messages.append(
            {
                "role": "tool",
                "tool_call_id": entry["provider_call_id"],
                "content": json.dumps(observation, ensure_ascii=False, default=str),
            }
        )
        if entry["name"] == "tool_search" and observation.get("ok"):
            discovered: list[str] = []
            for tool in (observation.get("content") or {}).get("tools") or []:
                name = str(tool.get("name") or "")
                if name in self.registry.definitions and name not in discovered:
                    discovered.append(name)
            # 下一轮同步工具目录和 schema 时保留新发现的工具。
            if discovered:
                candidate_names[:] = discovered + [
                    name for name in candidate_names if name not in discovered
                ]
        await self.events.emit(db, run.id, "tool_result", {"tool": entry["name"], **observation})

    async def _process_tool_batch(
        self,
        db: AsyncSession,
        run: AgentRun,
        state: AgentState,
        messages: list[dict[str, Any]],
        context: ToolExecutionContext,
        entries: list[dict[str, Any]],
        candidate_names: list[str],
        start_index: int = 0,
    ) -> bool:
        """Process every provider call or pause at exactly one approval boundary."""
        mode = str(run.state.get("approval_mode") or "confirm")
        for index in range(start_index, len(entries)):
            entry = entries[index]
            if not entry.get("executable", True):
                observation = {
                    "ok": False,
                    "tool_name": entry["name"],
                    "error_code": "tool_call_limit_exceeded",
                    "content": {"message": "单轮最多执行3个工具；该调用未执行。"},
                    "truncated": False,
                }
                await self._finish_batch_call(
                    db, run, state, messages, entry, observation, candidate_names
                )
                continue
            decision = self.registry.decide(entry["name"], mode)
            if decision == "deny":
                observation = {
                    "ok": False,
                    "tool_name": entry["name"],
                    "error_code": "permission_denied",
                    "content": {
                        "message": f"当前权限模式为 {mode}，不允许执行 {entry['name']}。"
                    },
                    "truncated": False,
                }
                await self._finish_batch_call(
                    db, run, state, messages, entry, observation, candidate_names
                )
                continue
            if decision == "approve":
                reason = (
                    f"工具 {entry['name']} 的风险等级为 {entry['risk']}，"
                    f"当前权限模式 {mode} 需要用户确认。"
                )
                approval = ApprovalRequest(
                    run_id=run.id,
                    tool_call_id=entry["tool_call_id"],
                    reason=reason,
                    mode=mode,
                    tool_risk=str(entry["risk"]),
                )
                db.add(approval)
                await db.flush()
                run.status = RunStatus.waiting_approval
                run.state = run.state | {
                    "pending_batch": {"entries": entries, "index": index},
                    "working_messages": messages,
                    "candidate_names": candidate_names,
                }
                await self._checkpoint(db, run, "waiting_approval", state)
                await self.events.emit(
                    db,
                    run.id,
                    "approval_required",
                    {
                        "approval_id": approval.id,
                        "tool": entry["name"],
                        "risk": entry["risk"],
                        "mode": mode,
                        "reason": reason,
                    },
                )
                await self._commit(db)
                return True
            result = await self.registry.dispatch(entry["name"], entry["arguments"], context)
            await self._finish_batch_call(
                db, run, state, messages, entry, asdict(result), candidate_names
            )
        return False

    async def _tool_loop(
        self,
        db: AsyncSession,
        run: AgentRun,
        state: AgentState,
        messages: list[dict[str, Any]],
        allowed_documents: list[str] | None,
        candidate_names: list[str] | None = None,
    ) -> str | None:
        if candidate_names is None:
            candidate_names, _ = await self._initial_tool_context(run, state)
        context = await self._tool_context(db, state, allowed_documents)
        pending = run.state.get("pending_batch")
        if pending:
            entries = list(pending["entries"])
            index = int(pending["index"])
            entry = entries[index]
            approval = await db.scalar(
                select(ApprovalRequest).where(ApprovalRequest.tool_call_id == entry["tool_call_id"])
            )
            if not approval or approval.status == "pending":
                return None
            messages = list(run.state.get("working_messages") or messages)
            if approval.status == "rejected":
                observation = {
                    "ok": False,
                    "tool_name": entry["name"],
                    "error_code": "approval_rejected",
                    "content": {"message": "用户拒绝执行此工具"},
                    "truncated": False,
                }
            else:
                observation = asdict(
                    await self.registry.dispatch(entry["name"], entry["arguments"], context)
                )
            await self._finish_batch_call(
                db, run, state, messages, entry, observation, candidate_names
            )
            run.state = {
                key: value
                for key, value in run.state.items()
                if key not in {"pending_batch", "working_messages"}
            }
            run.status = RunStatus.running
            await self.events.emit(db, run.id, "approval_resolved", {"decision": approval.status})
            if await self._process_tool_batch(
                db,
                run,
                state,
                messages,
                context,
                entries,
                candidate_names,
                start_index=index + 1,
            ):
                return None
            await self._commit(db)

        for round_no in range(state.round_no, 10):
            state.round_no = round_no + 1
            if run.status == RunStatus.cancelled:
                state.cancelled = True
                return None
            candidate_names = [
                name
                for name in dict.fromkeys(
                    [
                        *ALWAYS_AVAILABLE_TOOLS,
                        *(state.active_tool_allowlist or []),
                        *candidate_names,
                    ]
                )
                if name in self.registry.definitions
            ]
            tool_catalog = [
                asdict(self.registry.definitions[name].summary()) for name in candidate_names
            ]
            self._sync_tool_catalog(messages, tool_catalog)
            schemas = self.registry.schemas(candidate_names)
            messages, omissions = await self._compact_working_messages(
                run, messages, estimate_tokens(str(schemas))
            )
            if omissions:
                run.state = run.state | {"last_rebox_omissions": omissions}
                await self._checkpoint(db, run, f"compacted_round_{state.round_no}", state)
                await self.events.emit(
                    db,
                    run.id,
                    "context_compacted",
                    {"round": state.round_no, "omissions": omissions},
                )

            async def on_delta(delta: str) -> None:
                await self.events.publish(run.id, "text_delta", {"delta": delta})

            messages = sanitize_messages(messages)
            response = await self.provider.invoke(messages, schemas, on_delta)
            if not response.tool_calls:
                await self._save_transcript(db, run, messages, response.content)
                await self._commit(db)
                return response.content
            messages.append(
                {
                    "role": "assistant",
                    "content": response.content or None,
                    "tool_calls": [
                        {
                            "id": call.id,
                            "type": "function",
                            "function": {
                                "name": call.name,
                                "arguments": json.dumps(call.arguments, ensure_ascii=False),
                            },
                        }
                        for call in response.tool_calls
                    ],
                }
            )
            entries: list[dict[str, Any]] = []
            for index, call in enumerate(response.tool_calls):
                canonical_name = self.registry.resolve_provider_name(call.name)
                definition = self.registry.definitions.get(canonical_name)
                risk = definition.risk if definition else "write"
                record = ToolCall(
                    run_id=run.id,
                    provider_call_id=call.id,
                    tool_name=canonical_name,
                    arguments=call.arguments,
                    arguments_hash=self.registry.arguments_hash(canonical_name, call.arguments),
                    risk=risk,
                    status="pending",
                )
                db.add(record)
                await db.flush()
                entry = {
                    "tool_call_id": record.id,
                    "provider_call_id": call.id,
                    "name": canonical_name,
                    "arguments": call.arguments,
                    "risk": risk,
                    "executable": index < 3,
                }
                entries.append(entry)
                await self.events.emit(
                    db,
                    run.id,
                    "tool_call",
                    {"name": canonical_name, "arguments": call.arguments, "risk": risk},
                )
            if await self._process_tool_batch(
                db, run, state, messages, context, entries, candidate_names
            ):
                return None
            await self._checkpoint(db, run, f"round_{state.round_no}", state)
            await self._save_transcript(db, run, messages)
            await self._commit(db)
        raise RuntimeError("maximum agent rounds exceeded")

    async def _reduce_window_results(
        self, query: str, window_results: list[dict[str, Any]]
    ) -> list[dict[str, Any]]:
        """Hierarchically merge window conclusions without dropping a result."""
        target = max(2_000, int(self.settings.effective_input_budget * 0.65))
        current = window_results
        while estimate_tokens(json.dumps(current, ensure_ascii=False)) > target:
            batches: list[list[dict[str, Any]]] = []
            batch: list[dict[str, Any]] = []
            batch_tokens = 0
            for item in current:
                item_tokens = estimate_tokens(json.dumps(item, ensure_ascii=False))
                if batch and batch_tokens + item_tokens > target:
                    batches.append(batch)
                    batch = []
                    batch_tokens = 0
                batch.append(item)
                batch_tokens += item_tokens
            if batch:
                batches.append(batch)
            reduced: list[dict[str, Any]] = []
            for batch_items in batches:
                response = await self.provider.invoke(
                    [
                        {
                            "role": "system",
                            "content": WINDOW_REDUCER_SYSTEM_PROMPT,
                        },
                        {
                            "role": "user",
                            "content": window_reduce_prompt(query, batch_items),
                        },
                    ],
                    [],
                )
                reduced.append(
                    {
                        "manifests": [
                            item.get("manifest") or item.get("manifests") for item in batch_items
                        ],
                        "conclusion": response.content,
                    }
                )
            if len(reduced) >= len(current):
                raise RuntimeError("window conclusions cannot be reduced within model budget")
            current = reduced
        return current

    async def execute(self, db: AsyncSession, run_id: str) -> None:
        run = await db.get(AgentRun, run_id)
        if not run:
            raise KeyError(f"run not found: {run_id}")
        if run.status == RunStatus.cancelled:
            return
        run.status = RunStatus.running
        state_data = run.state.get("agent_state") or {}
        state = AgentState(
            run_id=run.id,
            notebook_id=run.notebook_id,
            user_id=run.user_id,
            **{
                key: value
                for key, value in state_data.items()
                if key not in {"run_id", "notebook_id", "user_id"}
            },
        )
        await self.events.emit(db, run.id, "run_started", {})
        await self._commit(db)
        try:
            input_message_id = str(run.state.get("input_message_id") or "") or None
            full_history = await self._message_history(db, run.conversation_id, input_message_id)
            stored_summary = await self._conversation_summary(db, run.conversation_id)
            probe_text, probe_remaining = self._apply_summary_boundary(
                full_history,
                stored_summary,
                keep_recent_turns=max(6, self.settings.context_keep_recent_turns),
            )
            allowed_documents = await self._allowed_documents(db, run)
            attachments = await self._attachments(db, run)
            envelope = QueryEnvelope(query=run.query, attachments=attachments)
            notebook_index = "当前 Notebook 的可用资料由 list_notebook_sources 查询。"
            skill_catalog = self.skills.summaries()
            candidate_names, tool_catalog = await self._initial_tool_context(run, state)
            tool_schemas = self.registry.schemas(candidate_names)
            evidence = await self._initial_rag_evidence(db, run, state, allowed_documents)

            def fixed_tokens(summary_text: str) -> int:
                payload = {"text": summary_text} if summary_text else None
                return self.context_builder.fixed_layer_tokens(
                    envelope,
                    summary=payload,
                    state=state,
                    notebook_index=notebook_index,
                    evidence=evidence,
                    skill_catalog=skill_catalog,
                    tool_catalog=tool_catalog,
                    tool_schemas=tool_schemas,
                )

            # Match ContextBuilder's packing limit before it decides on windowing.
            budget = int(self.settings.effective_input_budget * 0.95)
            reserve = int(budget * 0.25) if attachments else 512
            fixed_without = fixed_tokens("")
            fixed_with = fixed_tokens(probe_text) if probe_text else fixed_without
            if fixed_without - estimate_tokens(envelope.query) >= budget:
                raise ContextOverflowError("上下文超限：固定内容和工具 schema 已占满输入预算")
            if evidence and fixed_without >= budget:
                raise ContextOverflowError("上下文超限：全部 RAG 片段和固定内容已超过输入预算")
            try:
                history, summary_text, compact_budget = self._plan_history(
                    full_history,
                    probe_text,
                    probe_remaining,
                    budget,
                    reserve,
                    fixed_without,
                    fixed_with,
                )
            except ContextOverflowError as exc:
                if evidence or tool_schemas:
                    raise ContextOverflowError(f"上下文超限：{exc}") from exc
                raise
            # Compress cross-run history BEFORE build_packets decides on windowing.
            if compact_budget is not None:
                try:
                    history, new_summary, boundary = await self.compactor.compact_history(
                        history,
                        compact_budget,
                        previous_summary=summary_text,
                        summary_overhead=lambda text: fixed_tokens(text) - fixed_without,
                    )
                except ContextOverflowError as exc:
                    if evidence or tool_schemas:
                        raise ContextOverflowError(f"上下文超限：{exc}") from exc
                    raise
                summary_text = new_summary or ""
                if new_summary and boundary:
                    await self._persist_summary(
                        db,
                        run.conversation_id,
                        {"text": new_summary, "through_message_id": boundary},
                    )

            summary_payload = {"text": summary_text} if summary_text else None
            try:
                packets = self.context_builder.build_packets(
                    envelope,
                    history=history,
                    summary=summary_payload,
                    state=state,
                    notebook_index=notebook_index,
                    evidence=evidence,
                    skill_catalog=skill_catalog,
                    tool_catalog=tool_catalog,
                    tool_schemas=tool_schemas,
                )
            except ValueError as exc:
                if (evidence or tool_schemas) and "context layers leave no room" in str(exc):
                    raise ContextOverflowError(f"上下文超限：{exc}") from exc
                raise
            await self._attach_packet_evidence(db, run, packets, attachments, state)
            run.state = run.state | {
                "context_manifests": [asdict(item.manifest) for item in packets]
            }
            await self._checkpoint(db, run, "context_prepared", state)
            await self.events.emit(
                db,
                run.id,
                "context_manifest",
                {"mode": packets[0].manifest.mode, "windows": len(packets)},
            )
            await self._commit(db)

            if len(packets) == 1:
                answer = await self._tool_loop(
                    db,
                    run,
                    state,
                    packets[0].messages,
                    allowed_documents,
                    candidate_names,
                )
            else:
                window_results: list[dict[str, Any]] = []
                for index, packet in enumerate(packets, start=1):
                    response = await self.provider.invoke(packet.messages, [])
                    window_results.append(
                        {
                            "manifest": packet.manifest.attachments[0],
                            "conclusion": response.content,
                        }
                    )
                    await self.events.emit(
                        db,
                        run.id,
                        "attachment_window_completed",
                        {"index": index, "total": len(packets)},
                    )
                    await self._commit(db)
                window_results = await self._reduce_window_results(run.query, window_results)
                aggregate_messages = [
                    packets[0].messages[0],
                    {
                        "role": "user",
                        "content": aggregate_windows_prompt(run.query, window_results),
                    },
                ]
                answer = await self._tool_loop(
                    db, run, state, aggregate_messages, allowed_documents, candidate_names
                )
            if answer is None:
                return
            cited_ids = set(re.findall(r"\[evidence:([0-9a-f-]{36})\]", answer))
            valid_ids: set[str] = set()
            if cited_ids:
                valid_ids = set(
                    (
                        await db.execute(
                            select(Evidence.id).where(
                                Evidence.run_id == run.id,
                                Evidence.id.in_(cited_ids),
                            )
                        )
                    )
                    .scalars()
                    .all()
                )
            invalid_ids = cited_ids - valid_ids
            for invalid_id in invalid_ids:
                answer = answer.replace(f"[evidence:{invalid_id}]", "[无效引用已移除]")
            final_message = Message(
                conversation_id=run.conversation_id,
                role="assistant",
                content=answer,
            )
            db.add(final_message)
            await db.flush()
            for evidence_id in sorted(valid_ids):
                db.add(
                    Citation(
                        message_id=final_message.id,
                        evidence_id=evidence_id,
                        claim_text="Referenced by the final answer",
                    )
                )
            citation_rows = []
            if valid_ids:
                citation_rows = list(
                    (await db.execute(select(Evidence).where(Evidence.id.in_(valid_ids))))
                    .scalars()
                    .all()
                )
            run.status = RunStatus.completed
            run.state = run.state | {"agent_state": asdict(state), "final_answer": answer}
            await self._checkpoint(db, run, "completed", state)
            await self.events.emit(
                db,
                run.id,
                "run_completed",
                {
                    "answer": answer,
                    "citations": [
                        {
                            "evidence_id": item.id,
                            "source_kind": item.source_kind,
                            "source_id": item.source_id,
                            "section_title": item.section_title,
                            "page_start": item.page_start,
                            "page_end": item.page_end,
                            "char_start": item.char_start,
                            "char_end": item.char_end,
                        }
                        for item in citation_rows
                    ],
                },
            )
            await self._commit(db)
        except Exception as exc:
            self.events.discard_pending()
            run.status = RunStatus.failed
            run.error_message = str(exc)
            await self.events.emit(db, run.id, "run_failed", {"message": str(exc)})
            await self._commit(db)
            raise
