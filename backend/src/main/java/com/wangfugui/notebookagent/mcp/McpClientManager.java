package com.wangfugui.notebookagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.wangfugui.notebookagent.config.AppProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Discovers and invokes external MCP servers configured in mcp_servers.yaml. */
public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public record McpTool(String serverId, String name, String description, Map<String, Object> inputSchema,
                          String risk) {
        public String qualifiedName() {
            return "mcp." + serverId + "." + name;
        }
    }

    public record ServerConfig(String id, boolean enabled, String transport, String url, String command,
                               List<String> args, Map<String, String> env, String authToken,
                               List<String> allowedTools, String defaultRisk) {
    }

    private final AppProperties props;
    private final Map<String, ServerConfig> servers = new LinkedHashMap<>();
    private final Map<String, McpTool> tools = new LinkedHashMap<>();
    private final Map<String, String> errors = new LinkedHashMap<>();

    public McpClientManager(AppProperties props) {
        this.props = props;
    }

    public synchronized Map<String, ServerConfig> servers() {
        return new LinkedHashMap<>(servers);
    }

    public synchronized Map<String, McpTool> tools() {
        return new LinkedHashMap<>(tools);
    }

    public synchronized Map<String, String> errors() {
        return new LinkedHashMap<>(errors);
    }

    @SuppressWarnings("unchecked")
    public synchronized List<McpTool> discover() {
        servers.clear();
        tools.clear();
        errors.clear();
        Path configPath = Path.of(props.getMcpServersConfig());
        if (!Files.exists(configPath)) {
            return List.of();
        }
        List<Map<String, Object>> raw;
        try {
            Map<String, Object> config = YAML.readValue(Files.readString(configPath), Map.class);
            raw = (List<Map<String, Object>>) config.getOrDefault("servers", List.of());
        } catch (Exception e) {
            log.warn("failed to read mcp config: {}", e.getMessage());
            return List.of();
        }
        for (Map<String, Object> entry : raw) {
            ServerConfig server = toServerConfig(entry);
            servers.put(server.id(), server);
            if (!server.enabled()) {
                continue;
            }
            try {
                List<McpTool> discovered = listTools(server);
                for (McpTool tool : discovered) {
                    tools.put(tool.qualifiedName(), tool);
                }
            } catch (Exception e) {
                errors.put(server.id(), String.valueOf(e.getMessage()));
                log.warn("MCP server {} discovery failed: {}", server.id(), e.getMessage());
            }
        }
        return new ArrayList<>(tools.values());
    }

    private ServerConfig toServerConfig(Map<String, Object> entry) {
        String id = String.valueOf(entry.get("id"));
        boolean enabled = !Boolean.FALSE.equals(entry.get("enabled"));
        String transport = String.valueOf(entry.getOrDefault("transport", "stdio"));
        String url = entry.get("url") == null ? null : expand(String.valueOf(entry.get("url")));
        String command = entry.get("command") == null ? null : expand(String.valueOf(entry.get("command")));
        List<String> args = entry.get("args") instanceof List<?> a ? a.stream().map(String::valueOf).map(McpClientManager::expand).toList() : List.of();
        Map<String, String> env = new LinkedHashMap<>();
        if (entry.get("env") instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                env.put(String.valueOf(e.getKey()), expand(String.valueOf(e.getValue())));
            }
        }
        String authEnv = entry.get("auth_env") == null ? "" : String.valueOf(entry.get("auth_env"));
        String authToken = authEnv.isEmpty() ? "" : System.getenv().getOrDefault(authEnv, "");
        List<String> allowed = entry.get("allowed_tools") instanceof List<?> al
                ? al.stream().map(String::valueOf).toList() : List.of();
        String defaultRisk = String.valueOf(entry.getOrDefault("default_risk", "write"));
        return new ServerConfig(id, enabled, transport, url, command, args, env, authToken, allowed, defaultRisk);
    }

    private static String expand(String value) {
        if (value != null && value.matches("^\\$\\{[A-Z0-9_]+\\}$")) {
            String name = value.substring(2, value.length() - 1);
            return System.getenv().getOrDefault(name, "");
        }
        return value;
    }

    private List<McpTool> listTools(ServerConfig server) {
        McpSyncClient client = openClient(server);
        try {
            client.initialize();
            ListToolsResult result = client.listTools();
            List<McpTool> out = new ArrayList<>();
            for (Tool tool : result.tools()) {
                if (!server.allowedTools().isEmpty() && !server.allowedTools().contains(tool.name())) {
                    continue;
                }
                boolean readOnly = tool.annotations() != null && Boolean.TRUE.equals(tool.annotations().readOnlyHint());
                String risk = readOnly ? "read" : clampRisk(server.defaultRisk());
                out.add(new McpTool(server.id(), tool.name(),
                        tool.description() == null ? "External MCP tool" : tool.description(),
                        tool.inputSchema() == null ? Map.of() : tool.inputSchema(), risk));
            }
            return out;
        } finally {
            safeClose(client);
        }
    }

    public Map<String, Object> callTool(String qualifiedName, Map<String, Object> arguments) {
        McpTool tool; ServerConfig server;
        synchronized (this) {
            tool = tools.get(qualifiedName);
            if (tool == null) throw new IllegalArgumentException("unknown MCP tool: " + qualifiedName);
            server = servers.get(tool.serverId());
        }
        if (server == null || !server.enabled()) throw new IllegalArgumentException("disabled MCP server");
        McpSyncClient client = openClient(server);
        try {
            client.initialize();
            CallToolResult result = client.callTool(CallToolRequest.builder(tool.name())
                    .arguments(arguments == null ? Map.of() : arguments).build());
            List<Object> content = new ArrayList<>();
            for (Content item : result.content()) {
                if (item instanceof TextContent text) {
                    content.add(Map.of("type", "text", "text", text.text()));
                } else {
                    content.add(item.toString());
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("content", content);
            out.put("is_error", Boolean.TRUE.equals(result.isError()));
            return out;
        } finally {
            safeClose(client);
        }
    }

    public Map<String, Object> readResource(String serverId, String uri) {
        ServerConfig server;
        synchronized (this) { server = servers.get(serverId); }
        if (server == null || !server.enabled()) {
            throw new IllegalArgumentException("unknown MCP server: " + serverId);
        }
        McpSyncClient client = openClient(server);
        try {
            client.initialize();
            var result = client.readResource(
                    io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest.builder(uri).build());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("contents", result.contents());
            return out;
        } finally {
            safeClose(client);
        }
    }

    private McpSyncClient openClient(ServerConfig server) {
        var transport = io.modelcontextprotocol.json.McpJsonDefaults.getMapper();
        if ("streamable_http".equals(server.transport())) {
            java.net.URI uri = java.net.URI.create(server.url());
            String base = uri.getScheme() + "://" + uri.getRawAuthority();
            String endpoint = uri.getRawPath() == null || uri.getRawPath().isBlank() || "/".equals(uri.getRawPath())
                    ? "/mcp" : uri.getRawPath();
            if (uri.getRawQuery() != null) endpoint += "?" + uri.getRawQuery();
            var builder = HttpClientStreamableHttpTransport.builder(base).endpoint(endpoint);
            if (server.authToken() != null && !server.authToken().isBlank()) {
                builder.httpRequestCustomizer((rb, method, targetUri, body, ctx) ->
                        rb.header("Authorization", "Bearer " + server.authToken()));
            }
            return McpClient.sync(builder.build()).initializationTimeout(Duration.ofSeconds(60)).requestTimeout(Duration.ofSeconds(30)).build();
        }
        ServerParameters params = ServerParameters.builder(server.command())
                .args(server.args().toArray(new String[0]))
                .env(server.env())
                .build();
        StdioClientTransport transport2 = new StdioClientTransport(params, transport);
        return McpClient.sync(transport2).initializationTimeout(Duration.ofSeconds(60)).requestTimeout(Duration.ofSeconds(30)).build();
    }

    private static String clampRisk(String risk) {
        return List.of("read", "write", "destructive").contains(risk) ? risk : "write";
    }

    private static void safeClose(McpSyncClient client) {
        try {
            client.closeGracefully();
        } catch (Exception ignored) {
            // best effort
        }
    }
}
