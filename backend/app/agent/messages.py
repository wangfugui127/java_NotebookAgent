from __future__ import annotations

from collections.abc import Callable
from typing import Any

TOOL_RESULT_PLACEHOLDER = '{"truncated":true,"note":"工具已执行，结果原文在数据库"}'
TOOL_INCOMPLETE = (
    '{"ok":false,"error_code":"incomplete_tool_call",'
    '"content":{"message":"该次工具调用未完成"}}'
)


def is_system(message: dict[str, Any]) -> bool:
    return message.get("role") == "system"


def is_tool(message: dict[str, Any]) -> bool:
    return message.get("role") == "tool"


def has_tool_calls(message: dict[str, Any]) -> bool:
    return message.get("role") == "assistant" and bool(message.get("tool_calls"))


def turns(messages: list[dict[str, Any]]) -> list[list[dict[str, Any]]]:
    """Split conversation messages into turns at user boundaries.

    System messages stay attached to the turn that follows them. A turn never
    starts in the middle of an assistant tool-call group.
    """
    result: list[list[dict[str, Any]]] = []
    current: list[dict[str, Any]] = []
    for message in messages:
        if is_system(message):
            current.append(message)
            continue
        if message.get("role") == "user" and any(not is_system(item) for item in current):
            result.append(current)
            current = []
        current.append(message)
    if current:
        result.append(current)
    return result


def sanitize_messages(messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Guarantee provider-valid tool pairing.

    - Drops orphan ``tool`` messages (no preceding assistant tool_call).
    - Fills missing tool responses for an assistant ``tool_calls`` group so the
      pairing is always complete before the next non-tool message.
    """
    output: list[dict[str, Any]] = []
    pending: list[str] = []

    def flush() -> None:
        nonlocal pending
        for call_id in pending:
            output.append(
                {"role": "tool", "tool_call_id": call_id, "content": TOOL_INCOMPLETE}
            )
        pending = []

    for message in messages:
        role = message.get("role")
        if role == "assistant" and message.get("tool_calls"):
            if pending:
                flush()
            calls = [call.get("id") for call in message["tool_calls"] if call.get("id")]
            if calls:
                output.append(message)
                pending = list(calls)
            else:
                output.append({key: value for key, value in message.items() if key != "tool_calls"})
        elif role == "tool":
            call_id = message.get("tool_call_id")
            if call_id in pending:
                output.append(message)
                pending = [item for item in pending if item != call_id]
            # orphan tool messages are dropped
        else:
            if pending:
                flush()
            output.append(message)
    if pending:
        flush()
    return output


def trim_history_by_turns(
    messages: list[dict[str, Any]], keep_turns: int
) -> list[dict[str, Any]]:
    """Keep only the most recent complete turns, then sanitize pairing."""
    if keep_turns <= 0:
        return sanitize_messages(list(messages))
    parts = turns(messages)
    if len(parts) <= keep_turns:
        return sanitize_messages(list(messages))
    kept = [item for part in parts[-keep_turns:] for item in part]
    return sanitize_messages(kept)


def clear_old_tool_results(
    messages: list[dict[str, Any]],
    keep_recent: int,
    *,
    within: Callable[[list[dict[str, Any]]], bool] | None = None,
) -> tuple[list[dict[str, Any]], bool]:
    """Clear the smallest oldest-first batch that satisfies ``within``."""
    tool_indexes = [index for index, item in enumerate(messages) if is_tool(item)]
    protected = set(tool_indexes[-keep_recent:]) if keep_recent > 0 else set()
    changed = False
    output = list(messages)
    for index in tool_indexes:
        if index in protected or output[index].get("content") in {
            TOOL_RESULT_PLACEHOLDER,
            TOOL_INCOMPLETE,
        }:
            continue
        output[index] = {**output[index], "content": TOOL_RESULT_PLACEHOLDER}
        changed = True
        if within is not None and within(output):
            break
    return output, changed
