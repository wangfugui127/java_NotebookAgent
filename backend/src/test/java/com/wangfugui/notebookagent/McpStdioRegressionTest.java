package com.wangfugui.notebookagent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.wangfugui.notebookagent.config.AppProperties;
import com.wangfugui.notebookagent.mcp.McpClientManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class McpStdioRegressionTest {
    @TempDir Path dir;
    @Test void configuredStdioEnvironmentReachesRealChildProcess() throws Exception {
        Path config=dir.resolve("servers.yaml");
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        Map<String,Object> server=Map.of("id","fixture","transport","stdio","command",java,"args",List.of("-cp",System.getProperty("java.class.path"),StdioMcpFixture.class.getName()),"env",Map.of("NOTEBOOK_MCP_TEST_VALUE","passed"),"default_risk","read");
        new ObjectMapper(new YAMLFactory()).writeValue(config.toFile(),Map.of("servers",List.of(server)));
        AppProperties props=new AppProperties();props.setMcpServersConfig(config.toString());var manager=new McpClientManager(props);
        assertThat(manager.discover()).hasSize(1);assertThat(manager.errors()).isEmpty();
        var result=manager.callTool("mcp.fixture.echo",Map.of("text","hello"));assertThat(result.get("is_error")).isEqualTo(false);assertThat(result.toString()).contains("passed:hello");
    }
}
