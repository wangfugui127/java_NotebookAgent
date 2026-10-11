package com.wangfugui.notebookagent.api;

import com.wangfugui.notebookagent.agent.NativeTools;
import com.wangfugui.notebookagent.agent.ToolRegistry;
import com.wangfugui.notebookagent.core.ApiException;
import com.wangfugui.notebookagent.core.CurrentUser;
import com.wangfugui.notebookagent.domain.User;
import com.wangfugui.notebookagent.mcp.McpClientManager;
import com.wangfugui.notebookagent.skills.SkillCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/runtime")
public class RuntimeConfigController {

    private final McpClientManager mcp;
    private final SkillCatalog skills;

    public RuntimeConfigController(McpClientManager mcp, SkillCatalog skills) {
        this.mcp = mcp;
        this.skills = skills;
    }

    @GetMapping("/mcp")
    public Map<String, Object> mcpStatus(@CurrentUser User user) {
        List<Map<String, Object>> servers = new ArrayList<>();
        for (McpClientManager.ServerConfig server : mcp.servers().values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", server.id());
            row.put("enabled", server.enabled());
            row.put("transport", server.transport());
            servers.add(row);
        }
        return Map.of("servers", servers, "tools", new ArrayList<>(mcp.tools().keySet()), "errors", mcp.errors());
    }

    @PostMapping("/mcp/reload")
    public Map<String, Object> reloadMcp(@CurrentUser User user) {
        var tools = mcp.discover();
        List<String> names = tools.stream().map(McpClientManager.McpTool::qualifiedName).toList();
        return Map.of("tools", names, "errors", mcp.errors());
    }

    @PostMapping("/mcp/{serverId}/test")
    public Map<String, Object> testMcp(@PathVariable String serverId, @CurrentUser User user) {
        mcp.discover();
        if (!mcp.servers().containsKey(serverId)) {
            throw ApiException.notFound("MCP server not found");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", !mcp.errors().containsKey(serverId));
        result.put("error", mcp.errors().get(serverId));
        return result;
    }

    @GetMapping("/skills")
    public Map<String, Object> skillsStatus(@CurrentUser User user) {
        return Map.of("items", skills.summaries());
    }

    @PostMapping("/skills/reload")
    public Map<String, Object> reloadSkills(@CurrentUser User user) {
        ToolRegistry registry = new ToolRegistry();
        NativeTools.build(registry);
        registry.registerMcp(new ArrayList<>(mcp.tools().values()));
        skills.reload(registry.all().keySet());
        return Map.of("items", skills.summaries());
    }
}
