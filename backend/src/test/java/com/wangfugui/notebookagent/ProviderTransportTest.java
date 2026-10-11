package com.wangfugui.notebookagent;

import com.sun.net.httpserver.HttpServer;
import com.wangfugui.notebookagent.config.AppProperties;
import com.wangfugui.notebookagent.core.Json;
import com.wangfugui.notebookagent.providers.*;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ProviderTransportTest {
    HttpServer server;
    AppProperties props;
    String body;
    int status = 200;
    Map<String, Object> request;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            request = Json.readMap(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(status, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        server.start();
        props = new AppProperties();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        props.getDeepseek().setBaseUrl(base);
        props.getDeepseek().setApiKey("test-key");
        props.getSiliconflow().setBaseUrl(base);
        props.getSiliconflow().setApiKey("test-key");
        props.getSiliconflow().setEmbeddingDim(2);
    }
    @AfterEach void stop() { server.stop(0); }
    private static String chunk(Map<String, Object> delta) {
        return "data: " + Json.write(Map.of("choices", List.of(Map.of("delta", delta)))) + "\n\n";
    }
    @Test void fragmentedToolCallAndEmptyJsonObjectAreValid() {
        body = chunk(Map.of("content", "你好"))
                + chunk(Map.of("tool_calls", List.of(Map.of("index", 0, "id", "call-1", "function",
                    Map.of("name", "list_notebook_sources", "arguments", "{")))))
                + chunk(Map.of("tool_calls", List.of(Map.of("index", 0, "function", Map.of("arguments", "}")))))
                + "data: [DONE]\n\n";
        List<String> deltas = new ArrayList<>();
        var response = new DeepSeekProvider(props).invoke(List.of(Map.of("role", "user", "content", "hello")), List.of(), deltas::add);
        assertThat(response.content()).isEqualTo("你好");
        assertThat(deltas).containsExactly("你好");
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().getFirst().arguments()).isEmpty();
        assertThat(request.get("max_tokens")).isEqualTo(props.getMaxOutputTokens());
    }
    @Test void malformedToolArgumentsAreTagged() {
        body = chunk(Map.of("tool_calls", List.of(Map.of("index", 0, "id", "a", "function", Map.of("name", "x", "arguments", "bad-json")))))
                + "data: [DONE]\n\n";
        assertThat(new DeepSeekProvider(props).invoke(List.of(), List.of(), null).toolCalls().getFirst().arguments()).containsKey("_invalid_json");
    }
    @Test void providerHttpErrorsPropagate() {
        status = 503;
        body = "provider temporarily unavailable";
        assertThatThrownBy(() -> new DeepSeekProvider(props).invoke(List.of(), List.of(), null)).hasMessageContaining("503");
    }
    @Test void unfinishedStreamCannotPublishPartialAnswer() {
        body = chunk(Map.of("content", "partial"));
        assertThatThrownBy(() -> new DeepSeekProvider(props).invoke(List.of(),List.of(),null)).hasMessageContaining("completion marker");
    }
    @Test void lengthLimitedResponseIsReportedAsIncomplete() {
        body="data: " + Json.write(Map.of("choices",List.of(Map.of("delta",Map.of("content","partial"),"finish_reason","length"))))+"\n\ndata: [DONE]\n\n";
        assertThatThrownBy(() -> new DeepSeekProvider(props).invoke(List.of(),List.of(),null)).hasMessageContaining("incomplete");
    }
    @Test void embeddingResponseIsReorderedByInputIndex() {
        body = Json.write(Map.of("data", List.of(Map.of("index", 1, "embedding", List.of(0.0, 1.0)), Map.of("index", 0, "embedding", List.of(1.0, 0.0)))));
        var vectors = new SiliconFlowProvider(props).embed(List.of("first", "second"));
        assertThat(vectors.getFirst()).containsExactly(1.0f, 0.0f);
    }
    @Test void partialEmbeddingResponseFailsInsteadOfMarkingDocumentReady() {
        body = Json.write(Map.of("data", List.of(Map.of("index", 0, "embedding", List.of(1.0, 0.0)))));
        assertThatThrownBy(() -> new SiliconFlowProvider(props).embed(List.of("first", "second"))).hasMessageContaining("count mismatch");
    }
    @Test void incorrectEmbeddingDimensionFails() {
        body = Json.write(Map.of("data", List.of(Map.of("index", 0, "embedding", List.of(1.0)))));
        assertThatThrownBy(() -> new SiliconFlowProvider(props).embed(List.of("first"))).hasMessageContaining("dimension");
    }
}
