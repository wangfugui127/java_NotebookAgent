from __future__ import annotations

import json
import logging
from typing import Any, Literal

RagRoute = Literal["rag", "no_rag", "uncertain"]
logger = logging.getLogger(__name__)

RAG_ROUTER_SYSTEM_PROMPT = """你是 Notebook 论文预检索路由器。
你只能看到本轮用户问题，看不到历史、附件或 Notebook 内容。
判断回答前是否需要检索当前 Notebook 中已有论文的正文证据：
- rag：研究性事实问题、比较、核实或引用 Notebook 论文内容，需要已有论文证据。
- no_rag：闲聊、计算、改写已有回答、只处理本轮附件、列出资料名称，
  或寻找外部新论文；这些不需要预检索 Notebook 正文。
- uncertain：问题依赖你看不到的历史或附件才能判断，例如“这篇呢？”；不要猜测缺失的信息。
只输出一个 JSON 对象，格式严格为 {"route":"rag"}、{"route":"no_rag"}
或 {"route":"uncertain"}。不要输出其他内容。"""


class LLMRagRouter:
    def __init__(self, provider: Any) -> None:
        self.provider = provider

    async def route(self, query: str) -> RagRoute:
        try:
            response = await self.provider.invoke(
                [
                    {"role": "system", "content": RAG_ROUTER_SYSTEM_PROMPT},
                    {"role": "user", "content": query},
                ],
                [],
            )
            decision = json.loads(response.content.strip())
            if isinstance(decision, dict) and set(decision) == {"route"}:
                route = decision["route"]
                if route in {"rag", "no_rag", "uncertain"}:
                    return route
        except Exception:
            logger.exception("Notebook RAG route failed; leaving retrieval to the Agent Loop")
        return "uncertain"
