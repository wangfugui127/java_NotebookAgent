package com.wangfugui.spike.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Phase 0 probe: spawn the stdio MCP server as a subprocess and exercise it. */
public final class McpStdioProbe {

    public static void main(String[] args) throws Exception {
        String classpath = resolveClasspath();
        System.out.println("[mcp-stdio] subprocess classpath entries=" + classpath.split(java.io.File.pathSeparator).length);
        String logback = java.nio.file.Path.of("target/classes/logback-stdio.xml").toAbsolutePath().toString();
        ServerParameters params = ServerParameters.builder("java")
                .args("-Dlogback.configurationFile=" + logback,
                        "-cp", classpath, "com.wangfugui.spike.mcp.McpStdioServerMain")
                .build();
        StdioClientTransport transport = new StdioClientTransport(params, McpJsonDefaults.getMapper());
        try (McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(20))
                .build()) {
            client.initialize();
            ListToolsResult tools = client.listTools();
            List<String> names = tools.tools().stream().map(Tool::name).toList();
            System.out.println("[mcp-stdio] tools=" + names);
            if (!names.contains("echo")) {
                throw new IllegalStateException("echo tool not discovered over stdio");
            }
            CallToolResult result = client.callTool(CallToolRequest.builder("echo")
                    .arguments(Map.of("text", "hi"))
                    .build());
            String text = result.content().stream()
                    .filter(TextContent.class::isInstance)
                    .map(Content::toString)
                    .reduce("", (a, b) -> a + b);
            System.out.println("[mcp-stdio] callTool result=" + result.content());
            boolean ok = result.content().stream()
                    .filter(TextContent.class::isInstance)
                    .map(TextContent.class::cast)
                    .anyMatch(t -> t.text().contains("echo:hi"));
            if (!ok) {
                throw new IllegalStateException("unexpected echo result: " + text);
            }
            System.out.println("[mcp-stdio] ALL CAPABILITIES OK (stdio subprocess discovery + tool call)");
        }
    }

    private static String resolveClasspath() throws Exception {
        String cpFile = System.getProperty("spike.cpFile");
        if (cpFile != null) {
            String deps = java.nio.file.Files.readString(java.nio.file.Path.of(cpFile)).trim();
            String classes = java.nio.file.Path.of("target/classes").toAbsolutePath().toString();
            return classes + java.io.File.pathSeparator + deps;
        }
        return System.getProperty("java.class.path");
    }

    private McpStdioProbe() {
    }
}
