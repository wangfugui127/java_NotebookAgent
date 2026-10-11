package com.wangfugui.notebookagent.agent;

import java.util.LinkedHashMap;
import java.util.Map;

public record ToolObservation(boolean ok, String toolName, Map<String, Object> content,
                              String errorCode, boolean truncated) {

    public static ToolObservation ok(String toolName, Map<String, Object> content) {
        return new ToolObservation(true, toolName, content == null ? new LinkedHashMap<>() : content, null, false);
    }

    public static ToolObservation error(String toolName, String errorCode, String message) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("message", message);
        return new ToolObservation(false, toolName, content, errorCode, false);
    }

    public Map<String, Object> asPayload() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", ok);
        map.put("tool_name", toolName);
        map.put("content", content);
        map.put("error_code", errorCode);
        map.put("truncated", truncated);
        return map;
    }
}
