package com.wangfugui.notebookagent.agent;

import java.util.Map;

@FunctionalInterface
public interface ToolExecutor {
    ToolObservation execute(Map<String, Object> arguments, ToolContext context);
}
