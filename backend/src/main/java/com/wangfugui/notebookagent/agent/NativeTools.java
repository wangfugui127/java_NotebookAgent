package com.wangfugui.notebookagent.agent;

import com.wangfugui.notebookagent.core.Json;
import com.wangfugui.notebookagent.domain.Chunk;
import com.wangfugui.notebookagent.domain.Document;
import com.wangfugui.notebookagent.domain.DocumentVersion;
import com.wangfugui.notebookagent.domain.ExternalPaperResult;
import com.wangfugui.notebookagent.domain.TaskItem;
import com.wangfugui.notebookagent.retrieval.SearchHit;
import com.wangfugui.notebookagent.skills.SkillCatalog;

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

/** Native tool definitions and handlers. */
public final class NativeTools {

    public static final List<String> ALWAYS_AVAILABLE = List.of(
            "load_skill", "tool_search", "read_mcp_resource", "list_notebook_sources",
            "search_notebook", "get_notebook_items", "search_papers", "get_paper_details");

    public static void build(ToolRegistry registry) {
        registry.register(new ToolDefinition("task_update", "更新当前 Agent Run 的任务清单。",
                obj(Map.of("tasks", arr("object"))), "read", "native", NativeTools::taskUpdate));
        registry.register(new ToolDefinition("list_notebook_sources", "列出当前 Notebook 的资料与处理状态。",
                obj(Map.of("limit", Map.of("type", "integer", "default", 30))), "read", "native",
                NativeTools::listSources));
        registry.register(new ToolDefinition("search_notebook",
                "在当前 Notebook 执行 Dense、BM25 与可选 GraphRAG 检索。",
                obj(Map.of(
                        "query", Map.of("type", "string"),
                        "top_k", Map.of("type", "integer", "default", 8),
                        "retrieval_mode", Map.of("type", "string",
                                "enum", List.of("hybrid", "hybrid_graph", "layered", "auto", "comprehensive"),
                                "default", "auto"))),
                "read", "native", NativeTools::searchNotebook));
        registry.register(new ToolDefinition("get_notebook_items", "按 chunk_id 读取当前 Notebook 的精确原文。",
                obj(Map.of(
                        "chunk_ids", arr("string"),
                        "context_window", Map.of("type", "integer", "default", 0))),
                "read", "native", NativeTools::getItems));
        registry.register(new ToolDefinition("search_papers", "通过 OpenAlex 搜索外部论文，不写入 Notebook。",
                obj(Map.of(
                        "query", Map.of("type", "string"),
                        "year_from", Map.of("type", "integer"),
                        "year_to", Map.of("type", "integer"),
                        "limit", Map.of("type", "integer", "default", 10))),
                "read", "native", NativeTools::searchPapers));
        registry.register(new ToolDefinition("get_paper_details", "读取 OpenAlex 论文详情，不写入 Notebook。",
                obj(Map.of("paper_id", Map.of("type", "string"))), "read", "native", NativeTools::paperDetails));
        registry.register(new ToolDefinition("add_paper_to_notebook",
                "把 OpenAlex 外部论文加入当前 Notebook（有开放获取PDF则抓取全文，否则加入摘要与元数据）。",
                obj(Map.of(
                        "paper_ids", arr("string"),
                        "instruction", Map.of("type", "string", "default", ""))),
                "write", "native", NativeTools::addPapers));
        registry.register(new ToolDefinition("remove_notebook_source", "从当前 Notebook 删除指定来源及其检索索引。",
                obj(Map.of("document_ids", arr("string"))), "destructive", "native", NativeTools::removeSources));
        registry.register(new ToolDefinition("load_skill", "按名称加载完整 Skill 指令，Skill 不能扩大权限。",
                obj(Map.of("name", Map.of("type", "string"))), "read", "native", NativeTools::loadSkill));
        registry.register(new ToolDefinition("tool_search", "搜索短工具目录，最多返回 12 个候选。",
                obj(Map.of(
                        "query", Map.of("type", "string"),
                        "limit", Map.of("type", "integer", "default", 12))),
                "read", "native", NativeTools::toolSearch));
        registry.register(new ToolDefinition("read_mcp_resource", "读取已配置 MCP Server 的资源。",
                obj(Map.of(
                        "server_id", Map.of("type", "string"),
                        "uri", Map.of("type", "string"))),
                "read", "native", NativeTools::readMcpResource));
    }

    // ------------------------------------------------------------------ handlers

    private static ToolObservation taskUpdate(Map<String, Object> args, ToolContext ctx) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        Object tasks = args.get("tasks");
        if (tasks instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> task = new LinkedHashMap<>();
                    String title = m.get("title") == null ? "" : String.valueOf(m.get("title"));
                    task.put("title", title.length() > 512 ? title.substring(0, 512) : title);
                    task.put("status", m.get("status") == null ? "pending" : String.valueOf(m.get("status")));
                    normalized.add(task);
                }
            }
        }
        ctx.getTaskItems().deleteByRunId(ctx.getRunId());
        int ordinal = 0;
        for (Map<String, Object> task : normalized) {
            TaskItem item = new TaskItem();
            item.setRunId(ctx.getRunId());
            item.setTitle(String.valueOf(task.get("title")));
            item.setStatus(String.valueOf(task.get("status")));
            item.setOrdinal(ordinal++);
            ctx.getTaskItems().save(item);
        }
        ctx.getState().setTasks(normalized);
        return ToolObservation.ok("task_update", Map.of("tasks", normalized));
    }

    private static ToolObservation listSources(Map<String, Object> args, ToolContext ctx) {
        int limit = intArg(args, "limit", 30);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Document document : ctx.getDocuments().findByNotebookId(ctx.getNotebookId())) {
            if (items.size() >= limit) {
                break;
            }
            DocumentVersion active = document.getActiveVersionId() == null ? null
                    : ctx.getVersions().findById(document.getActiveVersionId()).orElse(null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("document_id", document.getId());
            row.put("title", document.getTitle());
            row.put("media_type", document.getMediaType());
            row.put("version_id", active == null ? null : active.getId());
            row.put("status", active == null ? "pending" : active.getStatus());
            items.add(row);
        }
        return ToolObservation.ok("list_notebook_sources", Map.of("items", items));
    }

    @SuppressWarnings("unchecked")
    private static ToolObservation searchNotebook(Map<String, Object> args, ToolContext ctx) {
        String query = String.valueOf(args.getOrDefault("query", ""));
        int topK = intArg(args, "top_k", 8);
        String mode = String.valueOf(args.getOrDefault("retrieval_mode", "auto"));
        Map<String, Object> result = ctx.getRetrieval().search(ctx.getNotebookId(), query,
                ctx.getAllowedDocumentIds(), mode, Math.min(Math.max(topK, 1), 20), null);
        List<Map<String, Object>> items = new ArrayList<>();
        if (result.get("items") instanceof List<?> hits) {
            for (Object hit : hits) {
                if (hit instanceof SearchHit sh) {
                    items.add(hitToMap(sh));
                }
            }
        }
        if (Boolean.TRUE.equals(result.get("graph_degraded"))) {
            ctx.getState().setGraphDegraded(true);
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("items", items);
        for (Map.Entry<String, Object> e : result.entrySet()) {
            if (!"items".equals(e.getKey())) {
                content.put(e.getKey(), e.getValue());
            }
        }
        return ToolObservation.ok("search_notebook", content);
    }

    private static ToolObservation getItems(Map<String, Object> args, ToolContext ctx) {
        List<String> ids = stringList(args.get("chunk_ids"));
        List<Map<String, Object>> items = new ArrayList<>();
        if (!ids.isEmpty()) {
            int window = Math.max(0, Math.min(10, intArg(args,"context_window",0)));
            Map<String,Chunk> selected = new LinkedHashMap<>();
            for (Chunk chunk : ctx.getChunks().findByIdInAndIsActiveTrue(ids)) {
                for (Chunk neighbor : window == 0 ? List.of(chunk) : ctx.getChunks().findByDocumentVersionIdAndIsActiveTrueOrderByOrdinal(chunk.getDocumentVersionId())) {
                    if (Math.abs(neighbor.getOrdinal()-chunk.getOrdinal()) <= window) selected.put(neighbor.getId(),neighbor);
                }
            }
            for (Chunk chunk : selected.values()) {
                DocumentVersion version = ctx.getVersions().findById(chunk.getDocumentVersionId()).orElse(null);
                if (version == null || ctx.getDocuments().findByIdAndNotebookId(version.getDocumentId(), ctx.getNotebookId()).isEmpty()
                        || ctx.getAllowedDocumentIds() != null && !ctx.getAllowedDocumentIds().contains(version.getDocumentId())) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("chunk_id", chunk.getId());
                row.put("document_version_id", chunk.getDocumentVersionId());
                row.put("chunk_type", chunk.getChunkType());
                row.put("block_ids", Json.readStringList(chunk.getBlockIds()));
                row.put("page_start", chunk.getPageStart());
                row.put("page_end", chunk.getPageEnd());
                row.put("char_start", chunk.getCharStart());
                row.put("char_end", chunk.getCharEnd());
                row.put("content", chunk.getContent());
                items.add(row);
            }
        }
        return ToolObservation.ok("get_notebook_items", Map.of("items", items));
    }

    private static ToolObservation searchPapers(Map<String, Object> args, ToolContext ctx) {
        String query = String.valueOf(args.getOrDefault("query", ""));
        Integer yearFrom = nullableInt(args.get("year_from"));
        Integer yearTo = nullableInt(args.get("year_to"));
        int limit = Math.min(Math.max(intArg(args, "limit", 10), 1), 30);
        List<Map<String, Object>> items = ctx.getOpenalex().search(query, yearFrom, yearTo, limit);
        return ToolObservation.ok("search_papers", Map.of("items", items, "persisted_to_notebook", false));
    }

    private static ToolObservation paperDetails(Map<String, Object> args, ToolContext ctx) {
        String paperId = String.valueOf(args.getOrDefault("paper_id", ""));
        Map<String, Object> item = ctx.getOpenalex().get(paperId);
        return ToolObservation.ok("get_paper_details", Map.of("item", item, "persisted_to_notebook", false));
    }

    private static ToolObservation addPapers(Map<String, Object> args, ToolContext ctx) {
        List<String> paperIds = stringList(args.get("paper_ids"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (String paperId : paperIds) {
            try {
                Map<String, Object> detail = ctx.getOpenalex().get(paperId);
                byte[] pdf = tryFetchPdf(detail.get("pdf_url") == null ? null : String.valueOf(detail.get("pdf_url")));
                String importedAs;
                byte[] content;
                String mediaType;
                String suffix;
                if (pdf != null) {
                    content = pdf;
                    mediaType = "application/pdf";
                    suffix = ".pdf";
                    importedAs = "pdf";
                } else {
                    String markdown = paperMarkdown(detail);
                    content = markdown.getBytes(StandardCharsets.UTF_8);
                    mediaType = "text/markdown";
                    suffix = ".md";
                    importedAs = "metadata";
                }
                String title = String.valueOf(detail.getOrDefault("title", paperId));
                var created = ctx.getSourceService().createSource(ctx.getNotebookId(), title, mediaType, content, suffix);
                ExternalPaperResult record = new ExternalPaperResult();
                record.setRunId(ctx.getRunId());
                record.setProvider("openalex");
                record.setProviderPaperId(String.valueOf(detail.getOrDefault("paper_id", paperId)));
                record.setData(Json.write(detail));
                ctx.getExternalPapers().save(record);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("paper_id", paperId);
                item.put("document_id", created.documentId());
                item.put("imported_as", importedAs);
                items.add(item);
            } catch (Exception e) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("paper_id", paperId);
                item.put("error", e.getMessage());
                items.add(item);
            }
        }
        return ToolObservation.ok("add_paper_to_notebook", Map.of("items", items));
    }

    private static ToolObservation removeSources(Map<String, Object> args, ToolContext ctx) {
        List<String> documentIds = stringList(args.get("document_ids"));
        List<String> removed = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        for (String documentId : documentIds) {
            Document document = ctx.getDocuments().findByIdAndNotebookId(documentId, ctx.getNotebookId()).orElse(null);
            if (document == null) {
                notFound.add(documentId);
                continue;
            }
            ctx.getDeleteService().deleteSource(document);
            removed.add(documentId);
        }
        return ToolObservation.ok("remove_notebook_source", Map.of("removed", removed, "not_found", notFound));
    }

    private static ToolObservation loadSkill(Map<String, Object> args, ToolContext ctx) {
        String name = String.valueOf(args.getOrDefault("name", ""));
        SkillCatalog catalog = ctx.getSkills();
        if (catalog == null || !catalog.has(name)) {
            return ToolObservation.error("load_skill", "skill_not_found", "unknown skill: " + name);
        }
        List<String> allowed = ctx.getState().getActiveToolAllowlist();
        if (allowed == null || allowed.isEmpty()) {
            allowed = List.copyOf(ctx.getRegistry().all().keySet());
        }
        Map<String, Object> loaded = catalog.load(name, allowed);
        ctx.getState().getLoadedSkills().put(name, loaded.get("version"));
        @SuppressWarnings("unchecked")
        List<String> effective = (List<String>) loaded.get("allowed_tools");
        ctx.getState().setActiveToolAllowlist(effective);
        return ToolObservation.ok("load_skill", loaded);
    }

    private static ToolObservation toolSearch(Map<String, Object> args, ToolContext ctx) {
        String query = String.valueOf(args.getOrDefault("query", "")).toLowerCase();
        int limit = Math.min(Math.max(intArg(args, "limit", 12), 1), 12);
        String[] words = query.split("\\s+");
        List<Map<String, Object>> summary = ctx.getRegistry().summaries(new ArrayList<>(ctx.getRegistry().all().keySet()));
        summary.sort((a, b) -> {
            int sa = score(a, words);
            int sb = score(b, words);
            if (sa != sb) {
                return Integer.compare(sb, sa);
            }
            return String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name")));
        });
        List<Map<String, Object>> tools = summary.stream().limit(limit).toList();
        return ToolObservation.ok("tool_search", Map.of("tools", tools));
    }

    private static int score(Map<String, Object> tool, String[] words) {
        String haystack = (tool.get("name") + " " + tool.get("description")).toLowerCase();
        int score = 0;
        for (String word : words) {
            if (!word.isBlank() && haystack.contains(word)) {
                score++;
            }
        }
        return score;
    }

    private static ToolObservation readMcpResource(Map<String, Object> args, ToolContext ctx) {
        if (ctx.getMcp() == null) {
            return ToolObservation.error("read_mcp_resource", "mcp_unavailable", "MCP is not configured");
        }
        String serverId = String.valueOf(args.getOrDefault("server_id", ""));
        String uri = String.valueOf(args.getOrDefault("uri", ""));
        Map<String, Object> result = ctx.getMcp().readResource(serverId, uri);
        return ToolObservation.ok("read_mcp_resource", result);
    }

    // ------------------------------------------------------------------ helpers

    public static Map<String, Object> hitToMap(SearchHit hit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("chunk_id", hit.getChunkId());
        row.put("text", hit.getText());
        row.put("score", hit.getScore());
        row.put("document_id", hit.getDocumentId());
        row.put("document_version_id", hit.getDocumentVersionId());
        row.put("title", hit.getTitle());
        row.put("page_start", hit.getPageStart());
        row.put("page_end", hit.getPageEnd());
        row.put("section_id", hit.getSectionId());
        row.put("section_title", hit.getSectionTitle());
        row.put("ordinal", hit.getOrdinal());
        row.put("chunk_type", hit.getChunkType());
        row.put("block_ids", hit.getBlockIds());
        row.put("char_start", hit.getCharStart()); row.put("char_end", hit.getCharEnd());
        row.put("sources", hit.getSources());
        return row;
    }

    private static byte[] tryFetchPdf(String pdfUrl) {
        if (pdfUrl == null || pdfUrl.isBlank()) {
            return null;
        }
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(pdfUrl)).timeout(Duration.ofSeconds(60)).GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            if (response.statusCode() < 400 && body.length > 4
                    && body[0] == '%' && body[1] == 'P' && body[2] == 'D' && body[3] == 'F') {
                return body;
            }
        } catch (Exception ignored) {
            // fall back to metadata
        }
        return null;
    }

    private static String paperMarkdown(Map<String, Object> detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(detail.getOrDefault("title", "Untitled")).append("\n\n");
        Object authors = detail.get("authors");
        if (authors instanceof List<?> list && !list.isEmpty()) {
            sb.append("作者：").append(String.join(", ", list.stream().map(String::valueOf).toList())).append("\n\n");
        }
        sb.append("年份：").append(detail.getOrDefault("year", "未知")).append("\n");
        sb.append("OpenAlex ID：").append(detail.getOrDefault("paper_id", "")).append("\n");
        if (detail.get("doi") != null) {
            sb.append("DOI：").append(detail.get("doi")).append("\n");
        }
        if (detail.get("url") != null) {
            sb.append("链接：").append(detail.get("url")).append("\n");
        }
        sb.append("\n## 摘要\n\n").append(detail.getOrDefault("abstract", "（无摘要）")).append("\n\n");
        sb.append("> 本文为外部论文元数据导入，未包含开放获取全文。");
        return sb.toString();
    }

    private static Map<String, Object> obj(Map<String, ?> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : properties.entrySet()) {
            props.put(entry.getKey(), entry.getValue());
        }
        schema.put("properties", props);
        schema.put("required", properties.entrySet().stream()
                .filter(e -> e.getValue() instanceof Map<?, ?> m && !m.containsKey("default"))
                .map(Map.Entry::getKey).filter(k -> !List.of("year_from", "year_to").contains(k)).toList());
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> arr(String itemType) {
        return Map.of("type", "array", "items", Map.of("type", itemType));
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static Integer nullableInt(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(x -> x != null && !String.valueOf(x).isBlank()).map(String::valueOf).toList();
        }
        return List.of();
    }

    private NativeTools() {
    }
}
