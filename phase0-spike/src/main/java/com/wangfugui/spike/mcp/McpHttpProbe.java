package com.wangfugui.spike.mcp;

import com.wangfugui.spike.SpikeApplication;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Phase 0 probe: streamable-HTTP MCP client. Verifies that requests without a bearer
 * token are rejected and that authenticated requests can list and call tools.
 */
public final class McpHttpProbe {

    public static void main(String[] args) {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(SpikeApplication.class)
                .properties("server.port=18081", "spring.main.banner-mode=off")
                .run()) {
            boolean rejected = probeNoToken();
            McpSyncClient client = buildClient(true);
            try {
                client.initialize();
                ListToolsResult tools = client.listTools();
                List<String> names = tools.tools().stream().map(Tool::name).toList();
                System.out.println("[mcp-http] authenticated tools=" + names);
                if (!names.contains("list_notebook_sources")) {
                    throw new IllegalStateException("tool not discovered over streamable HTTP");
                }
                CallToolResult result = client.callTool(CallToolRequest.builder("list_notebook_sources")
                        .arguments(Map.of("access_token", "whatever", "notebook_id", "nb-1"))
                        .build());
                boolean ok = result.content().stream()
                        .filter(TextContent.class::isInstance)
                        .map(TextContent.class::cast)
                        .anyMatch(t -> t.text().contains("nb-1"));
                System.out.println("[mcp-http] callTool result=" + result.content());
                if (!ok) {
                    throw new IllegalStateException("unexpected tool result");
                }
            } finally {
                client.closeGracefully();
            }
            if (!rejected) {
                throw new IllegalStateException("server did not reject a request without a bearer token");
            }
            System.out.println("[mcp-http] ALL CAPABILITIES OK (streamable HTTP + bearer-token security)");
        }
    }

    private static boolean probeNoToken() {
        McpSyncClient client = buildClient(false);
        try {
            client.initialize();
            client.listTools();
            System.out.println("[mcp-http] ERROR: unauthenticated request unexpectedly succeeded");
            return false;
        } catch (Exception e) {
            System.out.println("[mcp-http] unauthenticated request rejected: " + e.getClass().getSimpleName());
            return true;
        } finally {
            try {
                client.closeGracefully();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private static McpSyncClient buildClient(boolean withToken) {
        HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport
                .builder("http://localhost:18081")
                .endpoint("/mcp");
        if (withToken) {
            builder.httpRequestCustomizer((rb, method, uri, body, ctx) ->
                    rb.header("Authorization", "Bearer spike-token"));
        }
        return McpClient.sync(builder.build())
                .requestTimeout(Duration.ofSeconds(20))
                .build();
    }

    private McpHttpProbe() {
    }
}
