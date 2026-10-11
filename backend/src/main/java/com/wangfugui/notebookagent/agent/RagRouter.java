package com.wangfugui.notebookagent.agent;

import com.wangfugui.notebookagent.core.Json;
import com.wangfugui.notebookagent.prompts.Prompts;
import com.wangfugui.notebookagent.providers.DeepSeekProvider;
import com.wangfugui.notebookagent.providers.ProviderResponse;
import com.wangfugui.notebookagent.retrieval.GraphEntity;
import com.wangfugui.notebookagent.retrieval.GraphPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** LLM-based pre-retrieval router deciding whether Notebook RAG (and graph) is needed. */
public class RagRouter {

    private static final Logger log = LoggerFactory.getLogger(RagRouter.class);
    private static final Set<String> GRAPH_INTENTS = Set.of("method", "dataset", "result", "metric", "citation");
    private static final Set<String> GRAPH_ENTITY_KINDS = Set.of("paper", "dataset", "method", "result", "metric");

    private final DeepSeekProvider provider;

    public RagRouter(DeepSeekProvider provider) {
        this.provider = provider;
    }

    public RagDecision route(String query) {
        try {
            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "system", "content", Prompts.RAG_ROUTER_SYSTEM_PROMPT),
                    Map.of("role", "user", "content", query));
            ProviderResponse response = provider.invoke(messages, List.of(), null);
            Map<String, Object> decision = Json.readMap(stripFences(response.content()));
            Object routeObj = decision.get("route");
            if (!(routeObj instanceof String route)
                    || !Set.of("rag", "no_rag", "uncertain").contains(route)) {
                return RagDecision.uncertain();
            }
            if (!decision.keySet().equals(Set.of("route", "use_graph", "entities", "intents"))) {
                return new RagDecision(route, GraphPlan.none());
            }
            Object useGraph = decision.get("use_graph");
            Object entitiesObj = decision.get("entities");
            Object intentsObj = decision.get("intents");
            if (!(useGraph instanceof Boolean graph) || !(entitiesObj instanceof List<?> entities)
                    || !(intentsObj instanceof List<?> intents)) {
                return new RagDecision(route, GraphPlan.none());
            }
            List<GraphEntity> parsedEntities = new ArrayList<>();
            for (Object item : entities) {
                if (!(item instanceof Map<?, ?> m) || !m.keySet().equals(Set.of("name", "kind"))) {
                    return new RagDecision(route, GraphPlan.none());
                }
                String name = String.valueOf(m.get("name"));
                String kind = String.valueOf(m.get("kind"));
                if (!GRAPH_ENTITY_KINDS.contains(kind)) {
                    return new RagDecision(route, GraphPlan.none());
                }
                parsedEntities.add(new GraphEntity(name, kind));
            }
            List<String> parsedIntents = new ArrayList<>();
            for (Object item : intents) {
                if (!(item instanceof String s) || !GRAPH_INTENTS.contains(s)) {
                    return new RagDecision(route, GraphPlan.none());
                }
                parsedIntents.add(s);
            }
            if (!"rag".equals(route) || !graph) {
                return new RagDecision(route, GraphPlan.none());
            }
            LinkedHashSet<GraphEntity> uniqueEntities = new LinkedHashSet<>();
            for (GraphEntity e : parsedEntities) {
                if (e.name() != null && !e.name().trim().isEmpty()) {
                    uniqueEntities.add(new GraphEntity(e.name().trim(), e.kind()));
                }
            }
            List<GraphEntity> names = uniqueEntities.stream().limit(12).toList();
            List<String> selected = new ArrayList<>(new LinkedHashSet<>(parsedIntents));
            boolean graphEnabled = !names.isEmpty() && !selected.isEmpty();
            return new RagDecision(route, new GraphPlan(graphEnabled, names, selected));
        } catch (Exception e) {
            log.warn("Notebook RAG route failed; leaving retrieval to the Agent Loop: {}", e.getMessage());
            return RagDecision.uncertain();
        }
    }

    static String stripFences(String text) {
        if (text == null) {
            return "{}";
        }
        String t = text.strip();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                t = t.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return t;
    }
}
