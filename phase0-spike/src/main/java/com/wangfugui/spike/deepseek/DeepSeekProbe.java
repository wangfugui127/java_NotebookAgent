package com.wangfugui.spike.deepseek;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 0 probe: verify the OpenAI-compatible Java SDK can talk to DeepSeek with
 * (a) streaming chat deltas and (b) function/tool calling. Never prints the API key.
 */
public final class DeepSeekProbe {

    public static void main(String[] args) {
        Path envFile = Path.of(System.getProperty("user.dir")).getParent().resolve(".env");
        Map<String, String> env = Dotenv.load(envFile);
        String apiKey = env.get("DEEPSEEK_API_KEY");
        String baseUrl = env.getOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com");
        String model = env.getOrDefault("DEEPSEEK_MODEL", "deepseek-flash");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DEEPSEEK_API_KEY not configured in " + envFile);
        }
        System.out.println("[deepseek] model=" + model + " base=" + baseUrl + " key=SET(" + apiKey.length() + ")");

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        streamingText(client, model);
        toolCalling(client, model);
        client.close();
        System.out.println("[deepseek] ALL CAPABILITIES OK (streaming text + tool calling)");
    }

    private static void streamingText(OpenAIClient client, String model) {
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(model)
                .addUserMessage("用一句话说明什么是向量数据库。")
                .build();
        StringBuilder sb = new StringBuilder();
        AtomicInteger chunks = new AtomicInteger();
        try (StreamResponse<ChatCompletionChunk> stream = client.chat().completions().createStreaming(params)) {
            stream.stream().forEach(chunk -> {
                chunks.incrementAndGet();
                chunk.choices().forEach(c -> c.delta().content().ifPresent(sb::append));
            });
        }
        System.out.println("[deepseek] streaming chunks=" + chunks.get() + " text_len=" + sb.length());
        System.out.println("[deepseek] streamed text: " + sb.toString().strip());
        if (sb.length() == 0) {
            throw new IllegalStateException("streaming returned no text");
        }
    }

    private static void toolCalling(OpenAIClient client, String model) {
        FunctionParameters parameters = FunctionParameters.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                        "text", Map.of("type", "string", "description", "to echo"))))
                .putAdditionalProperty("required", JsonValue.from(List.of("text")))
                .build();
        ChatCompletionTool tool = ChatCompletionTool.ofFunction(ChatCompletionFunctionTool.builder()
                .function(FunctionDefinition.builder()
                        .name("echo_tool")
                        .description("Echo back the provided text.")
                        .parameters(parameters)
                        .build())
                .build());
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(model)
                .addTool(tool)
                .addUserMessage("请调用 echo_tool，把 text 设置为 hello-deepseek。")
                .build();
        ChatCompletion completion = client.chat().completions().create(params);
        List<ChatCompletionMessageToolCall> calls = completion.choices().stream()
                .map(ChatCompletion.Choice::message)
                .flatMap(m -> m.toolCalls().stream())
                .flatMap(List::stream)
                .toList();
        System.out.println("[deepseek] tool_calls=" + calls.size());
        if (calls.isEmpty()) {
            throw new IllegalStateException("model did not return a tool call");
        }
        for (ChatCompletionMessageToolCall call : calls) {
            var fn = call.function().orElseThrow();
            System.out.println("[deepseek] tool=" + fn.function().name()
                    + " arguments=" + fn.function().arguments());
        }
    }

    /** Minimal dotenv loader; ignores comments and blank lines. */
    static final class Dotenv {
        static Map<String, String> load(Path path) {
            Map<String, String> map = new java.util.HashMap<>();
            if (!Files.exists(path)) {
                return map;
            }
            try {
                for (String line : Files.readAllLines(path)) {
                    String t = line.strip();
                    if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) {
                        continue;
                    }
                    int i = t.indexOf('=');
                    String k = t.substring(0, i).strip();
                    String v = t.substring(i + 1).strip();
                    if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\""))
                            || (v.startsWith("'") && v.endsWith("'")))) {
                        v = v.substring(1, v.length() - 1);
                    }
                    map.put(k, v);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return map;
        }
    }

    private DeepSeekProbe() {
    }
}
