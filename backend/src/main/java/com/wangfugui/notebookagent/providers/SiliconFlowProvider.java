package com.wangfugui.notebookagent.providers;

import com.wangfugui.notebookagent.config.AppProperties;
import com.wangfugui.notebookagent.core.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SiliconFlow embeddings and rerank client. */
public class SiliconFlowProvider {

    private final AppProperties.Siliconflow config;
    private final HttpClient http;

    public SiliconFlowProvider(AppProperties props) {
        this.config = props.getSiliconflow();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    @SuppressWarnings("unchecked")
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new IllegalStateException("SILICONFLOW_API_KEY is not configured");
        }
        Map<String, Object> payload = Map.of("model", config.getEmbeddingModel(), "input", texts);
        Map<String, Object> response = post("/embeddings", payload);
        List<float[]> vectors = new ArrayList<>();
        Object data = response.get("data");
        if (data instanceof List<?> list) {
            List<?> ordered = list.stream().sorted(java.util.Comparator.comparingInt(item ->
                    item instanceof Map<?, ?> m && m.get("index") instanceof Number n ? n.intValue() : 0)).toList();
            int expectedIndex = 0;
            for (Object item : ordered) {
                if (!(item instanceof Map<?,?> indexed) || !(indexed.get("index") instanceof Number n) || n.intValue() != expectedIndex++)
                    throw new IllegalStateException("embedding response index mismatch");
                if (item instanceof Map<?, ?> m && m.get("embedding") instanceof List<?> vec) {
                    float[] arr = new float[vec.size()];
                    for (int i = 0; i < vec.size(); i++) {
                        arr[i] = ((Number) vec.get(i)).floatValue();
                    }
                    if (arr.length != config.getEmbeddingDim()) {
                        throw new IllegalArgumentException(
                                "embedding dimension " + arr.length + " != configured " + config.getEmbeddingDim());
                    }
                    vectors.add(arr);
                }
            }
        }
        if (vectors.size() != texts.size()) throw new IllegalStateException("embedding response count mismatch");
        return vectors;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> rerank(String query, List<String> documents, int topN) {
        if (documents.isEmpty()) {
            return List.of();
        }
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            List<Map<String, Object>> fallback = new ArrayList<>();
            for (int i = 0; i < Math.min(topN, documents.size()); i++) {
                fallback.add(Map.of("index", i, "relevance_score", 1.0 / (i + 1), "document", documents.get(i)));
            }
            return fallback;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.getRerankModel());
        payload.put("query", query);
        payload.put("documents", documents);
        payload.put("top_n", topN);
        payload.put("return_documents", false);
        Map<String, Object> response = post("/rerank", payload);
        List<Map<String, Object>> results = new ArrayList<>();
        Object data = response.get("results");
        if (data instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        row.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    results.add(row);
                }
            }
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> payload) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(config.getBaseUrl()) + path))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(payload), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException("siliconflow http " + response.statusCode() + ": " + response.body());
            }
            return Json.readMap(response.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("siliconflow request failed", e);
        }
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
