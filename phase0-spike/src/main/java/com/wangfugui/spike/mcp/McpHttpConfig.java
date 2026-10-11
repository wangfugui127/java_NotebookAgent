package com.wangfugui.spike.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Registers a Streamable-HTTP MCP server at /mcp with a bearer-token security
 * validator, mirroring the app exposing its own notebook tools with access_token.
 */
@Configuration
public class McpHttpConfig {

    private static final String TOKEN = "spike-token";

    @Bean
    public McpJsonMapper mcpJsonMapper() {
        return McpJsonDefaults.getMapper();
    }

    @Bean
    public HttpServletStreamableServerTransportProvider mcpTransportProvider(McpJsonMapper mapper) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(mapper)
                .mcpEndpoint("/mcp")
                .securityValidator(headers -> {
                    String auth = null;
                    for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                        if ("authorization".equalsIgnoreCase(e.getKey()) && !e.getValue().isEmpty()) {
                            auth = e.getValue().get(0);
                        }
                    }
                    if (auth == null || !auth.equals("Bearer " + TOKEN)) {
                        throw new ServerTransportSecurityException(401, "missing or invalid bearer token");
                    }
                })
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider provider) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(provider, "/mcp", "/mcp/*");
        registration.setAsyncSupported(true);
        return registration;
    }

    @Bean(destroyMethod = "close")
    public McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider provider) {
        return McpServer.sync(provider)
                .serverInfo("spike-mcp-http", "1.0.0")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .toolCall(
                        Tool.builder("list_notebook_sources", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "access_token", Map.of("type", "string"),
                                        "notebook_id", Map.of("type", "string")),
                                "required", List.of("access_token", "notebook_id"))).build(),
                        (exchange, request) -> CallToolResult.builder()
                                .addTextContent("{\"items\":[],\"notebook_id\":\""
                                        + request.arguments().get("notebook_id") + "\"}")
                                .build())
                .build();
    }
}
