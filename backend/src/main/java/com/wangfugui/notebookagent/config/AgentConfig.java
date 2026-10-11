package com.wangfugui.notebookagent.config;

import com.wangfugui.notebookagent.agent.NativeTools;
import com.wangfugui.notebookagent.agent.ToolRegistry;
import com.wangfugui.notebookagent.mcp.McpClientManager;
import com.wangfugui.notebookagent.skills.SkillCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    @Bean
    public SkillCatalog skillCatalog(AppProperties props) {
        return new SkillCatalog(props);
    }

    @Bean
    public McpClientManager mcpClientManager(AppProperties props) {
        return new McpClientManager(props);
    }

    @Bean
    public ApplicationRunner mcpAndSkillBootstrap(McpClientManager mcp, SkillCatalog skills) {
        return args -> {
            try {
                mcp.discover();
            } catch (Exception e) {
                log.warn("MCP discovery at startup failed: {}", e.getMessage());
            }
            try {
                ToolRegistry registry = new ToolRegistry();
                NativeTools.build(registry);
                registry.registerMcp(new ArrayList<>(mcp.tools().values()));
                skills.reload(registry.all().keySet());
            } catch (Exception e) {
                log.warn("skill catalog load at startup failed: {}", e.getMessage());
            }
        };
    }

    /** Keep imports meaningful even if the registry is only built per run. */
    @SuppressWarnings("unused")
    private List<String> nativeToolNames() {
        return NativeTools.ALWAYS_AVAILABLE;
    }
}
