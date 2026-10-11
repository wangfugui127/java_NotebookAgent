package com.wangfugui.spike.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Minimal STDIO MCP server exposing an `echo` tool, spawned by McpStdioProbe. */
public final class McpStdioServerMain {

    public static void main(String[] args) throws Exception {
        McpJsonMapper mapper = McpJsonDefaults.getMapper();
        StdioServerTransportProvider provider = new StdioServerTransportProvider(mapper);
        McpSyncServer server = McpServer.sync(provider)
                .serverInfo("spike-stdio", "1.0.0")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .toolCall(
                        Tool.builder("echo", Map.of(
                                "type", "object",
                                "properties", Map.of("text", Map.of("type", "string")),
                                "required", List.of("text"))).build(),
                        (exchange, request) -> CallToolResult.builder()
                                .addTextContent("echo:" + request.arguments().get("text"))
                                .build())
                .build();
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(latch::countDown));
        latch.await();
        server.close();
    }

    private McpStdioServerMain() {
    }
}
