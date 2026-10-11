package com.wangfugui.notebookagent.providers;

import com.wangfugui.notebookagent.config.AppProperties;
import com.wangfugui.notebookagent.core.Json;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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
import java.util.function.Consumer;

/** OpenAI-compatible streaming chat client targeting DeepSeek. */
public class DeepSeekProvider {

    private final AppProperties props;
    private final AppProperties.Deepseek config;
    private final HttpClient http;

    public DeepSeekProvider(AppProperties props) {
        this.props = props;
        this.config = props.getDeepseek();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    public String modelName() {
        return config.getModel();
    }

    /** invoke with streaming deltas and tool calling. */
    public ProviderResponse invoke(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                                   Consumer<String> onDelta) {
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new IllegalStateException("DEEPSEEK_API_KEY is not configured");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.getModel());
        payload.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            payload.put("tools", tools);
            payload.put("tool_choice", "auto");
        }
        if (com.wangfugui.notebookagent.agent.ContextBuilder.estimateTokens(Json.write(messages)) +
                com.wangfugui.notebookagent.agent.ContextBuilder.estimateTokens(Json.write(tools)) > props.effectiveInputBudget()) {
            throw new IllegalStateException("provider input exceeds configured context budget");
        }
        payload.put("max_tokens", props.getMaxOutputTokens());
        payload.put("stream", true);
        payload.put("stream_options", Map.of("include_usage", true));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(config.getBaseUrl()) + "/chat/completions"))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(payload), StandardCharsets.UTF_8))
                .build();

        StringBuilder content = new StringBuilder();
        Map<Integer, ToolAccumulator> toolAcc = new LinkedHashMap<>();
        Map<String, Object> usage = new LinkedHashMap<>();

        try {
            HttpResponse<java.io.InputStream> response = http.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new RuntimeException("deepseek http " + response.statusCode() + ": " + body);
            }
            boolean completed = false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) { completed = true; break; }
                    if (data.isEmpty()) continue;
                    Map<String,Object> chunk = Json.mapper().readValue(data, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
                    if (chunk.get("error") != null) throw new IllegalStateException("provider stream error: " + Json.write(chunk.get("error")));
                    if (chunk.get("choices") instanceof List<?> choices && !choices.isEmpty() && choices.getFirst() instanceof Map<?,?> choice) {
                        Object finish = choice.get("finish_reason");
                        if ("length".equals(finish) || "content_filter".equals(finish)) throw new IllegalStateException("provider response incomplete: " + finish);
                        if ("stop".equals(finish) || "tool_calls".equals(finish)) completed=true;
                    }
                    handleChunk(chunk, content, toolAcc, usage, onDelta);
                }
            }
            if (!completed) throw new IllegalStateException("provider stream ended without completion marker");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("deepseek invoke failed", e);
        }

        List<ProviderToolCall> toolCalls = new ArrayList<>();
        for (ToolAccumulator acc : toolAcc.values()) {
            Map<String, Object> args;
            try {
                args = Json.mapper().readValue(acc.arguments.toString().isBlank() ? "{}" : acc.arguments.toString(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                args = Map.of("_invalid_json", acc.arguments.toString());
            }
            toolCalls.add(new ProviderToolCall(acc.id.toString(), acc.name.toString(), args));
        }
        return new ProviderResponse(content.toString(), toolCalls, usage, new LinkedHashMap<>());
    }

    @SuppressWarnings("unchecked")
    private void handleChunk(Map<String, Object> chunk, StringBuilder content,
                             Map<Integer, ToolAccumulator> toolAcc, Map<String, Object> usage,
                             Consumer<String> onDelta) {
        Object usageObj = chunk.get("usage");
        if (usageObj instanceof Map<?, ?> u && !u.isEmpty()) {
            usage.clear();
            for (Map.Entry<?, ?> e : u.entrySet()) {
                usage.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        Object choices = chunk.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        Object choice = list.get(0);
        if (!(choice instanceof Map<?, ?> cm)) {
            return;
        }
        Object delta = cm.get("delta");
        if (!(delta instanceof Map<?, ?> dm)) {
            return;
        }
        Object text = dm.get("content");
        if (text instanceof String s && !s.isEmpty()) {
            content.append(s);
            if (onDelta != null) {
                onDelta.accept(s);
            }
        }
        Object calls = dm.get("tool_calls");
        if (calls instanceof List<?> callList) {
            for (Object c : callList) {
                if (!(c instanceof Map<?, ?> call)) {
                    continue;
                }
                int index = call.get("index") instanceof Number n ? n.intValue() : 0;
                ToolAccumulator acc = toolAcc.computeIfAbsent(index, k -> new ToolAccumulator());
                if (call.get("id") instanceof String id) {
                    acc.id.append(id);
                }
                Object fn = call.get("function");
                if (fn instanceof Map<?, ?> f) {
                    if (f.get("name") instanceof String name) {
                        acc.name.append(name);
                    }
                    if (f.get("arguments") instanceof String arg) {
                        acc.arguments.append(arg);
                    }
                }
            }
        }
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static final class ToolAccumulator {
        final StringBuilder id = new StringBuilder();
        final StringBuilder name = new StringBuilder();
        final StringBuilder arguments = new StringBuilder();
    }
}
