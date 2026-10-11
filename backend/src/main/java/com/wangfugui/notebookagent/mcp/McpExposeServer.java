package com.wangfugui.notebookagent.mcp;

import com.wangfugui.notebookagent.agent.NativeTools;
import com.wangfugui.notebookagent.config.AppProperties;
import com.wangfugui.notebookagent.core.Json;
import com.wangfugui.notebookagent.domain.Chunk;
import com.wangfugui.notebookagent.domain.Document;
import com.wangfugui.notebookagent.domain.DocumentVersion;
import com.wangfugui.notebookagent.providers.DeepSeekProvider;
import com.wangfugui.notebookagent.providers.OpenAlexProvider;
import com.wangfugui.notebookagent.providers.SiliconFlowProvider;
import com.wangfugui.notebookagent.agent.RagRouter;
import com.wangfugui.notebookagent.core.TokenService;
import com.wangfugui.notebookagent.repository.ChunkRepository;
import com.wangfugui.notebookagent.repository.DocumentRepository;
import com.wangfugui.notebookagent.repository.DocumentVersionRepository;
import com.wangfugui.notebookagent.repository.NotebookRepository;
import com.wangfugui.notebookagent.repository.SectionRepository;
import com.wangfugui.notebookagent.retrieval.MilvusStore;
import com.wangfugui.notebookagent.retrieval.Neo4jStore;
import com.wangfugui.notebookagent.retrieval.RetrievalService;
import com.wangfugui.notebookagent.retrieval.SearchHit;
import io.jsonwebtoken.Claims;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exposes the notebook tools over streamable-HTTP MCP, authorized by an access_token argument. */
@Configuration
public class McpExposeServer {

    public record Exposure(boolean enabled, String path, java.util.Set<String> tools) {}
    public static Exposure readExposure(String configPath) {
        Map<String,Object> config = Map.of();
        try {
            var path = java.nio.file.Path.of(configPath);
            if (java.nio.file.Files.exists(path)) config = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                    .readValue(java.nio.file.Files.readString(path),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
        } catch (Exception e) { throw new IllegalStateException("invalid MCP expose config",e); }
        String path = String.valueOf(config.getOrDefault("path","/mcp"));
        if (!path.matches("/[A-Za-z0-9_/-]+") || path.startsWith("/api/") || path.equals("/health")) throw new IllegalArgumentException("invalid MCP endpoint path");
        java.util.Set<String> tools = new java.util.LinkedHashSet<>(List.of("list_notebook_sources","search_notebook","get_notebook_items","search_papers","get_paper_details"));
        if (config.get("allowed_tools") instanceof List<?> list) {
            tools.retainAll(list.stream().map(String::valueOf).toList());
        }
        return new Exposure(!Boolean.FALSE.equals(config.get("enabled")),path,tools);
    }
    @Bean public Exposure mcpExposure(AppProperties props) { return readExposure(props.getMcpExposeConfig()); }

    @Bean
    public McpJsonMapper mcpJsonMapper() {
        return McpJsonDefaults.getMapper();
    }

    @Bean
    public HttpServletStreamableServerTransportProvider mcpStreamableTransport(McpJsonMapper mapper, Exposure exposure) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(mapper)
                .mcpEndpoint(exposure.path())
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider provider, Exposure exposure) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(provider, exposure.path(), exposure.path()+"/*");
        registration.setEnabled(exposure.enabled());
        registration.setAsyncSupported(true);
        return registration;
    }

    @Bean(destroyMethod = "close")
    public McpSyncServer notebookMcpServer(AppProperties props, TokenService tokens, NotebookRepository notebooks,
                                           DocumentRepository documents, com.wangfugui.notebookagent.repository.UserRepository users, DocumentVersionRepository versions,
                                           ChunkRepository chunks, SectionRepository sections,
                                           HttpServletStreamableServerTransportProvider provider, Exposure exposure) {
        McpSyncServer server = McpServer.sync(provider)
                .serverInfo("NotebookAgent", "0.1.0")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .toolCall(tool("list_notebook_sources", "列出当前 Notebook 的资料与处理状态。",
                                Map.of("access_token", Map.of("type", "string"), "notebook_id", Map.of("type", "string"))),
                        (exchange, request) -> {
                            String notebookId = authorize(props, tokens, notebooks, users, request);
                            if (notebookId == null) {
                                return error("unauthorized");
                            }
                            List<Map<String, Object>> items = new ArrayList<>();
                            for (Document document : documents.findByNotebookId(notebookId)) {
                                DocumentVersion active = document.getActiveVersionId() == null ? null
                                        : versions.findById(document.getActiveVersionId()).orElse(null);
                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put("document_id", document.getId());
                                row.put("title", document.getTitle());
                                row.put("version_id", active == null ? null : active.getId());
                                row.put("status", active == null ? "pending" : active.getStatus());
                                items.add(row);
                            }
                            return ok(Map.of("items", items));
                        })
                .toolCall(tool("search_notebook", "在当前 Notebook 执行 Dense、BM25 与可选 GraphRAG 检索。",
                                Map.of("access_token", Map.of("type", "string"),
                                        "notebook_id", Map.of("type", "string"),
                                        "query", Map.of("type", "string"),
                                        "retrieval_mode", Map.of("type", "string", "default", "auto"),
                                        "top_k", Map.of("type", "integer", "default", 8))),
                        (exchange, request) -> {
                            String notebookId = authorize(props, tokens, notebooks, users, request);
                            if (notebookId == null) {
                                return error("unauthorized");
                            }
                            try (RetrievalService service = new RetrievalService(new MilvusStore(props), new Neo4jStore(props),
                                    new SiliconFlowProvider(props), new RagRouter(new DeepSeekProvider(props)),
                                    chunks, sections, versions, documents)) {
                            String query = String.valueOf(request.arguments().get("query"));
                            String mode = String.valueOf(request.arguments().getOrDefault("retrieval_mode", "auto"));
                            int topK = request.arguments().get("top_k") instanceof Number n ? n.intValue() : 8;
                            Map<String, Object> result = service.search(notebookId, query, null, mode,
                                    Math.min(Math.max(topK, 1), 20), null);
                            List<Map<String, Object>> items = new ArrayList<>();
                            if (result.get("items") instanceof List<?> list) {
                                for (Object item : list) {
                                    if (item instanceof SearchHit hit) {
                                        items.add(NativeTools.hitToMap(hit));
                                    }
                                }
                            }
                            return ok(Map.of("items", items));
                            }
                        })
                .toolCall(tool("get_notebook_items", "按 chunk_id 读取当前 Notebook 的精确原文。",
                                Map.of("access_token", Map.of("type", "string"),
                                        "notebook_id", Map.of("type", "string"),
                                        "chunk_ids", Map.of("type", "array", "items", Map.of("type", "string")))),
                        (exchange, request) -> {
                            String notebookId = authorize(props, tokens, notebooks, users, request);
                            if (notebookId == null) {
                                return error("unauthorized");
                            }
                            List<String> ids = new ArrayList<>();
                            if (request.arguments().get("chunk_ids") instanceof List<?> list) {
                                list.forEach(x -> ids.add(String.valueOf(x)));
                            }
                            List<Map<String, Object>> items = new ArrayList<>();
                            for (Chunk chunk : chunks.findByIdInAndIsActiveTrue(ids)) {
                                DocumentVersion version = versions.findById(chunk.getDocumentVersionId()).orElse(null);
                                if (version == null || documents.findByIdAndNotebookId(version.getDocumentId(), notebookId).isEmpty()) continue;
                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put("chunk_id", chunk.getId());
                                row.put("document_version_id", chunk.getDocumentVersionId());
                                row.put("page_start", chunk.getPageStart());
                                row.put("page_end", chunk.getPageEnd());
                                row.put("char_start", chunk.getCharStart());
                                row.put("char_end", chunk.getCharEnd());
                                row.put("block_ids", Json.readStringList(chunk.getBlockIds()));
                                row.put("content", chunk.getContent());
                                items.add(row);
                            }
                            return ok(Map.of("items", items));
                        })
                .toolCall(tool("search_papers", "通过 OpenAlex 搜索外部论文，不写入 Notebook。",
                                Map.of("access_token", Map.of("type", "string"),
                                        "notebook_id", Map.of("type", "string"),
                                        "query", Map.of("type", "string"),
                                        "limit", Map.of("type", "integer", "default", 10))),
                        (exchange, request) -> {
                            String notebookId = authorize(props, tokens, notebooks, users, request);
                            if (notebookId == null) {
                                return error("unauthorized");
                            }
                            String query = String.valueOf(request.arguments().get("query"));
                            int limit = request.arguments().get("limit") instanceof Number n ? n.intValue() : 10;
                            return ok(Map.of("items", new OpenAlexProvider(props).search(query, null, null,
                                    Math.min(Math.max(limit, 1), 30))));
                        })
                .toolCall(tool("get_paper_details", "读取 OpenAlex 论文详情，不写入 Notebook。",
                                Map.of("access_token", Map.of("type", "string"),
                                        "notebook_id", Map.of("type", "string"),
                                        "paper_id", Map.of("type", "string"))),
                        (exchange, request) -> {
                            String notebookId = authorize(props, tokens, notebooks, users, request);
                            if (notebookId == null) {
                                return error("unauthorized");
                            }
                            return ok(Map.of("item", new OpenAlexProvider(props)
                                    .get(String.valueOf(request.arguments().get("paper_id")))));
                        })
                .build();
        for (Tool tool : new ArrayList<>(server.listTools())) if (!exposure.enabled() || !exposure.tools().contains(tool.name())) server.removeTool(tool.name());
        return server;
    }

    private static Tool tool(String name, String description, Map<String, ?> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>(properties);
        schema.put("properties", props);
        schema.put("required", properties.entrySet().stream().filter(e -> e.getValue() instanceof Map<?, ?> m && !m.containsKey("default")).map(Map.Entry::getKey).toList());
        return Tool.builder(name, schema).description(description).build();
    }

    private static String authorize(AppProperties props, TokenService tokens, NotebookRepository notebooks, com.wangfugui.notebookagent.repository.UserRepository users,
                                    io.modelcontextprotocol.spec.McpSchema.CallToolRequest request) {
        Object token = request.arguments().get("access_token");
        Object notebookId = request.arguments().get("notebook_id");
        if (token == null || notebookId == null) {
            return null;
        }
        try {
            Claims claims = tokens.decode(String.valueOf(token), "access");
            if (users.findById(claims.getSubject()).filter(com.wangfugui.notebookagent.domain.User::isActive).isEmpty()) return null;
            if (notebooks.findByIdAndOwnerId(String.valueOf(notebookId), claims.getSubject()).isEmpty()) {
                return null;
            }
            return String.valueOf(notebookId);
        } catch (Exception e) {
            return null;
        }
    }

    private static CallToolResult ok(Map<String, Object> content) {
        return CallToolResult.builder().addTextContent(Json.write(content)).build();
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(Json.write(Map.of("error", message))).isError(true).build();
    }
}
