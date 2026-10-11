package com.wangfugui.notebookagent.agent;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.wangfugui.notebookagent.core.Json;
import com.wangfugui.notebookagent.mcp.McpClientManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registry of native and MCP tools with provider-name aliasing and permission decisions. */
public class ToolRegistry {
    public String argumentsError(String name, Map<String, Object> arguments) {
        ToolDefinition def = definitions.get(name);
        if (def == null) return "unknown tool: " + name;
        if (arguments != null && arguments.containsKey("_invalid_json")) return "malformed JSON arguments";
        try {
            var registry = com.networknt.schema.SchemaRegistry.withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12);
            var errors = registry.getSchema(Json.mapper().valueToTree(def.schema()))
                    .validate(Json.mapper().valueToTree(arguments == null ? Map.of() : arguments));
            return errors.isEmpty() ? null : errors.toString();
        } catch (Exception e) { return "invalid tool schema or arguments: " + e.getMessage(); }
    }


    private final Map<String, ToolDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, String> aliasToCanonical = new LinkedHashMap<>();

    public void register(ToolDefinition definition) {
        definitions.put(definition.name(), definition);
    }

    public void registerMcp(List<McpClientManager.McpTool> tools) {
        definitions.entrySet().removeIf(e -> "mcp".equals(e.getValue().source()));
        for (McpClientManager.McpTool tool : tools) {
            Map<String, Object> schema = tool.inputSchema() == null ? Map.of("type", "object") : tool.inputSchema();
            register(new ToolDefinition(tool.qualifiedName(), tool.description(), schema, tool.risk(), "mcp",
                    (args, ctx) -> {
                        Map<String, Object> result = ctx.getMcp().callTool(tool.qualifiedName(), args);
                        boolean isError = Boolean.TRUE.equals(result.get("is_error"));
                        Map<String, Object> content = new LinkedHashMap<>();
                        content.put("content", result.get("content"));
                        return isError
                                ? ToolObservation.error(tool.qualifiedName(), "mcp_tool_error", String.valueOf(content))
                                : ToolObservation.ok(tool.qualifiedName(), content);
                    }));
        }
    }

    public ToolDefinition get(String name) {
        return definitions.get(name);
    }

    public Map<String, ToolDefinition> all() {
        return definitions;
    }

    public List<Map<String, Object>> summaries(List<String> names) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : new java.util.LinkedHashSet<>(names)) {
            ToolDefinition def = definitions.get(name);
            if (def == null) {
                continue;
            }
            out.add(Map.of("name", def.name(), "description", def.description(), "risk", def.risk()));
        }
        return out;
    }

    public List<Map<String, Object>> schemas(List<String> names) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : new java.util.LinkedHashSet<>(names)) {
            ToolDefinition def = definitions.get(name);
            if (def == null) {
                continue;
            }
            String provider = providerName(def.name());
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", provider);
            function.put("description", def.description());
            function.put("parameters", def.schema());
            out.add(Map.of("type", "function", "function", function));
        }
        return out;
    }

    public String decide(String name, String mode) {
        ToolDefinition def = definitions.get(name);
        String risk = def == null ? "write" : def.risk();
        if ("read".equals(risk)) {
            return "allow";
        }
        if ("auto".equals(mode)) {
            return "allow";
        }
        if ("read_only".equals(mode)) {
            return "deny";
        }
        return "approve";
    }

    public String providerName(String name) {
        String alias = name.replace(".", "__");
        if (alias.length() <= 64) {
            aliasToCanonical.put(alias, name);
            return alias;
        }
        String digest = sha256Hex(name).substring(0, 8);
        String shortened = alias.substring(0, 55) + "_" + digest;
        aliasToCanonical.put(shortened, name);
        return shortened;
    }

    public String resolveProviderName(String providerName) {
        if (aliasToCanonical.containsKey(providerName)) {
            return aliasToCanonical.get(providerName);
        }
        String candidate = providerName.replace("__", ".");
        return definitions.containsKey(candidate) ? candidate : providerName;
    }

    public static String argumentsHash(String name, Map<String, Object> arguments) {
        String canonical;
        try {
            canonical = Json.mapper().copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (Exception e) {
            canonical = String.valueOf(arguments);
        }
        return sha256Hex(name + ":" + canonical);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
