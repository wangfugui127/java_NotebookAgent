package com.wangfugui.notebookagent.ingestion;

import com.wangfugui.notebookagent.core.Json;
import com.wangfugui.notebookagent.prompts.Prompts;
import com.wangfugui.notebookagent.providers.DeepSeekProvider;
import com.wangfugui.notebookagent.providers.ProviderResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Extracts experiment-graph entities/relations per document section via the LLM. */
public final class GraphExtractor {

    public record GraphResult(List<Map<String, Object>> entities, List<Map<String, Object>> relations) {
    }

    public static List<GraphResult> extractSection(String sectionTitle, List<String> segments, DeepSeekProvider provider) {
        List<GraphResult> results = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            results.add(new GraphResult(new ArrayList<>(), new ArrayList<>()));
        }
        if (provider == null || segments.isEmpty()) {
            return results;
        }
        StringBuilder user = new StringBuilder("章节标题：").append(sectionTitle).append("\n\n");
        for (int i = 0; i < segments.size(); i++) {
            user.append("[片段 ").append(i).append("]\n").append(segments.get(i)).append("\n\n");
        }
        try {
            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "system", "content", Prompts.SECTION_GRAPH_SYSTEM_PROMPT),
                    Map.of("role", "user", "content", user.toString()));
            ProviderResponse response = provider.invoke(messages, List.of(), null);
            List<Object> parsed = Json.mapper().readValue(stripFences(response.content()), new com.fasterxml.jackson.core.type.TypeReference<List<Object>>() {});
            for (Object element : parsed) {
                if (!(element instanceof Map<?, ?> m)) {
                    continue;
                }
                Object indexObj = m.get("experiments") != null ? m.get("index") : null;
                int index = indexObj instanceof Number n ? n.intValue() : -1;
                if (index < 0 || index >= results.size()) {
                    continue;
                }
                Object experiments = m.get("experiments");
                if (experiments instanceof List<?> expList) {
                    results.set(index, flatten(expList));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("graph extraction failed", e);
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    static GraphResult flatten(List<?> experiments) {
        Map<String, Map<String, Object>> entities = new LinkedHashMap<>();
        List<Map<String, Object>> relations = new ArrayList<>();
        for (Object expObj : experiments) {
            if (!(expObj instanceof Map<?, ?> exp)) {
                continue;
            }
            String expKey = normalizeKey(str(exp.get("key"), str(exp.get("name"), "experiment")));
            String expName = str(exp.get("name"), expKey);
            entity(entities, expKey, expName, "Experiment");
            for (Object methodObj : list(exp.get("methods"))) {
                if (methodObj instanceof Map<?, ?> method) {
                    String key = normalizeKey(str(method.get("key"), str(method.get("name"), "method")));
                    entity(entities, key, str(method.get("name"), key), "Method");
                    relations.add(relation(expKey, key, "USES_METHOD"));
                }
            }
            for (Object datasetObj : list(exp.get("datasets"))) {
                if (datasetObj instanceof Map<?, ?> dataset) {
                    String key = normalizeKey(str(dataset.get("key"), str(dataset.get("name"), "dataset")));
                    entity(entities, key, str(dataset.get("name"), key), "Dataset");
                    relations.add(relation(expKey, key, "USES_DATASET"));
                }
            }
            for (Object resultObj : list(exp.get("results"))) {
                if (resultObj instanceof Map<?, ?> result) {
                    String key = normalizeKey(str(result.get("key"), str(result.get("name"), "result")));
                    entity(entities, key, str(result.get("name"), key), "Result");
                    relations.add(relation(expKey, key, "REPORTS_RESULT"));
                    Object metric = result.get("metric");
                    if (metric instanceof Map<?, ?> metricMap && metricMap.get("name") != null) {
                        String metricKey = normalizeKey(str(metricMap.get("key"), String.valueOf(metricMap.get("name"))));
                        entity(entities, metricKey, String.valueOf(metricMap.get("name")), "Metric");
                        relations.add(relation(key, metricKey, "MEASURES"));
                    }
                }
            }
        }
        return new GraphResult(new ArrayList<>(entities.values()), relations);
    }

    private static void entity(Map<String, Map<String, Object>> entities, String key, String name, String kind) {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("key", key);
        entity.put("name", name);
        entity.put("kind", kind);
        entities.putIfAbsent(key, entity);
    }

    private static Map<String, Object> relation(String source, String target, String kind) {
        Map<String, Object> relation = new LinkedHashMap<>();
        relation.put("source", source);
        relation.put("target", target);
        relation.put("kind", kind);
        relation.put("confidence", 0.7);
        return relation;
    }

    static String normalizeKey(String name) {
        return name == null ? "entity" : name.trim().toLowerCase().replaceAll("\\s+", "-");
    }

    private static String str(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    static String stripFences(String text) {
        if (text == null) {
            return "[]";
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

    private GraphExtractor() {
    }
}
