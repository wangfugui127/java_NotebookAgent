package com.wangfugui.notebookagent.providers;

import java.util.Map;

public record ProviderToolCall(String id, String name, Map<String, Object> arguments) {
}
