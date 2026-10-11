package com.wangfugui.notebookagent.agent;

import java.util.Map;

public record ToolDefinition(String name, String description, Map<String, Object> schema, String risk,
                             String source, ToolExecutor executor) {
}
