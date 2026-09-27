from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from typing import Any, Literal

from app.retrieval.types import GraphEntity, GraphPlan

RagRoute = Literal["rag", "no_rag", "uncertain"]
GRAPH_INTENTS = {"method", "dataset", "result", "metric", "citation"}
GRAPH_ENTITY_KINDS = {"paper", "dataset", "method", "result", "metric"}
logger = logging.getLogger(__name__)

RAG_ROUTER_SYSTEM_PROMPT = """你是 Notebook 论文预检索路由器。
你只能看到本轮用户问题，看不到历史、附件或 Notebook 内容。
判断回答前是否需要检索当前 Notebook 中已有论文的正文证据：
- rag：研究性事实问题、比较、核实或引用 Notebook 论文内容，需要已有论文证据。
- no_rag：闲聊、计算、改写已有回答、只处理本轮附件、列出资料名称，
  或寻找外部新论文；这些不需要预检索 Notebook 正文。
- uncertain：问题依赖你看不到的历史或附件才能判断，例如“这篇呢？”；不要猜测缺失的信息。
仅在 route=rag 时，再判断是否需要图关系证据。图中 Paper 通过 HAS_EXPERIMENT
连接 Experiment；Experiment 通过 USES_METHOD、USES_DATASET、REPORTS_RESULT
连接方法、数据集、结果；Result 通过 MEASURES 连接指标；CITES 表示引用关系。
只有问题确实询问这些实体之间的关系，且能从问题本身提取实体名称时，use_graph 才为 true。
entities 是问题中明确出现的名称及类别，kind 只能是 paper、dataset、method、result、metric；
不要猜测别名或补造实体。特别是“在数据集 X 上”，必须将 X 标记为 dataset，
这样图里找不到 X 时不能改用别的实验。
intents 可多选：method、dataset、result、metric、citation。
例如“比较论文 A、B 在数据集 X 上的方法和结果”：
{"route":"rag","use_graph":true,"entities":[{"name":"A","kind":"paper"},
{"name":"B","kind":"paper"},{"name":"X","kind":"dataset"}],
"intents":["dataset","method","result"]}。
只输出一个 JSON 对象，恰好包含 route、use_graph、entities、intents 四个字段。
不需要图时 use_graph=false，entities 和 intents 均为 []。不要输出其他内容。"""


@dataclass(frozen=True)
class RagDecision:
    route: RagRoute
    graph: GraphPlan = GraphPlan()


class LLMRagRouter:
    def __init__(self, provider: Any) -> None:
        self.provider = provider

    async def route(self, query: str) -> RagDecision:
        try:
            response = await self.provider.invoke(
                [
                    {"role": "system", "content": RAG_ROUTER_SYSTEM_PROMPT},
                    {"role": "user", "content": query},
                ],
                [],
            )
            decision = json.loads(response.content.strip())
            if isinstance(decision, dict):
                route = decision["route"]
                if not isinstance(route, str) or route not in {"rag", "no_rag", "uncertain"}:
                    return RagDecision("uncertain")
                if set(decision) == {"route", "use_graph", "entities", "intents"}:
                    use_graph = decision["use_graph"]
                    entities = decision["entities"]
                    intents = decision["intents"]
                    if (
                        isinstance(use_graph, bool)
                        and isinstance(entities, list)
                        and all(
                            isinstance(item, dict)
                            and set(item) == {"name", "kind"}
                            and isinstance(item["name"], str)
                            and isinstance(item["kind"], str)
                            and item["kind"] in GRAPH_ENTITY_KINDS
                            for item in entities
                        )
                        and isinstance(intents, list)
                        and all(isinstance(item, str) and item in GRAPH_INTENTS for item in intents)
                    ):
                        if route != "rag" or not use_graph:
                            return RagDecision(route)
                        names = tuple(
                            dict.fromkeys(
                                GraphEntity(item["name"].strip(), item["kind"])
                                for item in entities
                                if item["name"].strip()
                            )
                        )[:12]
                        selected = tuple(dict.fromkeys(intents))
                        return RagDecision(
                            route, GraphPlan(bool(names and selected), names, selected)
                        )
                return RagDecision(route)
        except Exception:
            logger.exception("Notebook RAG route failed; leaving retrieval to the Agent Loop")
        return RagDecision("uncertain")
