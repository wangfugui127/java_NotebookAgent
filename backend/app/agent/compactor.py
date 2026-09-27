from __future__ import annotations

import json
from collections.abc import Callable
from typing import Any

from app.agent.context_builder import estimate_tokens, messages_tokens
from app.agent.messages import clear_old_tool_results, sanitize_messages
from app.core.config import Settings
from app.prompts.agent import CONTEXT_COMPACT_SYSTEM_PROMPT, context_compact_prompt

SUMMARY_PREFIX = "[较早对话摘要]"


class ContextOverflowError(ValueError):
    """Raised when context cannot be reduced within the model input budget."""


class ContextCompactor:
    """Compact only above budget while protecting the six newest turns.

    Cross-run chat history comes from the ``messages`` table (questions and
    final answers only, no tool results), so history compaction goes straight
    to summarization. The live loop clears old tool results at most once per
    run before switching to summarization.
    """

    def __init__(self, settings: Settings, provider: Any) -> None:
        self.settings = settings
        self.provider = provider

    @staticmethod
    def _partition(
        messages: list[dict[str, Any]],
    ) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
        prompt_systems: list[dict[str, Any]] = []
        summary_systems: list[dict[str, Any]] = []
        convo: list[dict[str, Any]] = []
        for item in messages:
            if item.get("role") != "system":
                convo.append(item)
            elif str(item.get("content") or "").startswith(SUMMARY_PREFIX):
                summary_systems.append(item)
            else:
                prompt_systems.append(item)
        return prompt_systems + summary_systems, convo

    @staticmethod
    def _turns(convo: list[dict[str, Any]]) -> list[list[dict[str, Any]]]:
        turns: list[list[dict[str, Any]]] = []
        current: list[dict[str, Any]] = []
        for item in convo:
            if item.get("role") == "user" and current:
                turns.append(current)
                current = []
            current.append(item)
        if current:
            turns.append(current)
        return turns

    @staticmethod
    def _flatten(turns: list[list[dict[str, Any]]]) -> list[dict[str, Any]]:
        return [item for turn in turns for item in turn]

    def _recent_split(
        self, systems: list[dict[str, Any]], convo: list[dict[str, Any]], keep: int
    ) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
        turns = self._turns(convo)
        older_turns = turns[:-keep] if len(turns) > keep else []
        recent_turns = turns[-keep:] if turns else []
        older = [*systems, *self._flatten(older_turns)]
        recent = self._flatten(recent_turns)
        return older, recent

    @staticmethod
    def _previous_summary(systems: list[dict[str, Any]]) -> str:
        return "\n".join(
            str(item.get("content") or "")[len(SUMMARY_PREFIX) :].strip()
            for item in systems
            if str(item.get("content") or "").startswith(SUMMARY_PREFIX)
        ).strip()

    @staticmethod
    def _split_by_tokens(items: list[Any], budget: int) -> list[list[Any]]:
        batches: list[list[Any]] = []
        batch: list[Any] = []
        used = 0
        for item in items:
            cost = estimate_tokens(json.dumps(item, ensure_ascii=False, default=str)) + 16
            if batch and used + cost > budget:
                batches.append(batch)
                batch = []
                used = 0
            batch.append(item)
            used += cost
        if batch:
            batches.append(batch)
        return batches

    async def _summarize_once(self, previous_summary: str, payload: str) -> str | None:
        try:
            response = await self.provider.invoke(
                [
                    {"role": "system", "content": CONTEXT_COMPACT_SYSTEM_PROMPT},
                    {"role": "user", "content": context_compact_prompt(previous_summary, payload)},
                ],
                [],
            )
        except Exception:
            return None
        return str(response.content or "").strip() or None

    async def _summarize(
        self, previous_summary: str, messages: list[dict[str, Any]], budget: int
    ) -> str | None:
        """Summarize messages, batching when one request would exceed the model budget."""
        budget = max(512, budget)
        batches = self._split_by_tokens(messages, budget)
        partials: list[str] = []
        for index, batch in enumerate(batches):
            payload = json.dumps(batch, ensure_ascii=False, default=str)
            content = await self._summarize_once(
                previous_summary if index == 0 else "", payload
            )
            if content is None:
                return None
            partials.append(content)
        combined = "\n".join(partials)
        while len(partials) > 1 and estimate_tokens(combined) > budget:
            regrouped = self._split_by_tokens(partials, budget)
            if len(regrouped) >= len(partials):
                break
            reduced: list[str] = []
            for batch in regrouped:
                content = await self._summarize_once(
                    previous_summary,
                    json.dumps(batch, ensure_ascii=False, default=str),
                )
                if content is None:
                    return None
                reduced.append(content)
            partials = reduced
            combined = "\n".join(partials)
        return combined

    def _summary_call_budget(self) -> int:
        return max(1_000, int(self.settings.effective_input_budget * 0.6))

    async def compact_history(
        self,
        messages: list[dict[str, Any]],
        budget: int,
        previous_summary: str = "",
        summary_overhead: Callable[[str], int] | None = None,
    ) -> tuple[list[dict[str, Any]], str | None, str | None]:
        """Compact cross-run history (no tool results) down to ``budget`` tokens.

        Returns ``(kept_messages, summary_text, boundary_message_id)``. When the
        history already fits, everything is returned untouched. Raises
        ``ContextOverflowError`` when it cannot converge.
        """
        overhead = summary_overhead or (
            lambda text: estimate_tokens(str({"text": text})) if text else 0
        )
        if not messages:
            return messages, None, None
        if messages_tokens(messages) <= budget:
            return messages, None, None
        turns = self._turns(messages)
        keep = max(6, self.settings.context_keep_recent_turns)
        if len(turns) <= keep:
            actual = messages_tokens(messages)
            raise ContextOverflowError(
                f"history exceeds budget with its newest {len(turns)} turns protected: "
                f"{actual} > {budget}"
            )
        older = self._flatten(turns[:-keep])
        recent_turns = turns[-keep:]
        summary = await self._summarize(previous_summary, older, self._summary_call_budget())
        if not summary:
            raise ContextOverflowError("history summary failed; cannot reduce context")
        boundary = str(older[-1].get("message_id") or "") or None

        def total() -> int:
            return messages_tokens(self._flatten(recent_turns)) + overhead(summary)

        actual = total()
        if actual > budget:
            raise ContextOverflowError(
                f"history still exceeds budget with six turns protected: {actual} > {budget}"
            )
        return self._flatten(recent_turns), summary, boundary

    async def compact(
        self,
        messages: list[dict[str, Any]],
        *,
        keep_recent_turns: int | None = None,
        tool_cleanup_done: bool = False,
        budget: int | None = None,
    ) -> tuple[list[dict[str, Any]], list[dict[str, Any]], str | None]:
        budget = budget if budget is not None else self.settings.effective_input_budget
        keep = max(6, keep_recent_turns or self.settings.context_keep_recent_turns)
        omissions: list[dict[str, Any]] = []
        current = [dict(item) for item in messages]
        if messages_tokens(current) <= budget:
            return current, omissions, None

        if not tool_cleanup_done:
            systems, convo = self._partition(current)
            older, recent = self._recent_split(systems, convo, keep)
            older, cleared = clear_old_tool_results(
                older,
                self.settings.context_keep_recent_tool_results,
                within=lambda candidate: messages_tokens([*candidate, *recent]) <= budget,
            )
            if cleared:
                omissions.append(
                    {"layer": "messages", "reason": "over budget: cleared old tool result contents"}
                )
            current = [*older, *recent]
            if messages_tokens(current) <= budget:
                return sanitize_messages(current), omissions, None

        systems, convo = self._partition(current)
        older, recent = self._recent_split(systems, convo, keep)
        older_convo = [item for item in older if item.get("role") != "system"]
        if not older_convo:
            raise ContextOverflowError(
                f"loop context exceeds budget with six recent turns protected: "
                f"{messages_tokens(current)} > {budget}"
            )
        previous_summary = self._previous_summary(systems)
        summary = await self._summarize(
            previous_summary, older_convo, self._summary_call_budget()
        )
        if summary is None:
            raise ContextOverflowError(
                "loop summary failed while context remains over budget: "
                f"{messages_tokens(current)} > {budget}"
            )

        prompt_systems = [
            item
            for item in systems
            if not str(item.get("content") or "").startswith(SUMMARY_PREFIX)
        ]
        summary_message = {"role": "system", "content": f"{SUMMARY_PREFIX}\n{summary}"}
        compacted = [*prompt_systems, summary_message, *recent]
        actual = messages_tokens(compacted)
        if actual > budget:
            raise ContextOverflowError(
                f"loop context still exceeds budget with six turns protected: {actual} > {budget}"
            )
        omissions.append({"layer": "messages", "reason": "older turns summarized"})
        return sanitize_messages(compacted), omissions, summary
