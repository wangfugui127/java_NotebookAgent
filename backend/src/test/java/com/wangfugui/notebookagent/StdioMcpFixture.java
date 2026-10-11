package com.wangfugui.notebookagent;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.*;
public class StdioMcpFixture {
    public static void main(String[] args) throws Exception {
        var protocolOut=System.out; System.setOut(System.err);
        var transport=new StdioServerTransportProvider(McpJsonDefaults.getMapper(),System.in,protocolOut);
        var server=McpServer.sync(transport).serverInfo("test-stdio","1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .toolCall(McpSchema.Tool.builder("echo",Map.of("type","object","properties",Map.of("text",Map.of("type","string")),"required",List.of("text"))).build(),
                        (e,r)->McpSchema.CallToolResult.builder().addTextContent(System.getenv("NOTEBOOK_MCP_TEST_VALUE")+":"+r.arguments().get("text")).build()).build();
        try {
            new java.util.concurrent.CountDownLatch(1).await();
        } finally { server.close(); }
    }
}
