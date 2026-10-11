package com.wangfugui.notebookagent.providers;

import java.util.List;
import java.util.Map;

public record ProviderResponse(
        String content,
        List<ProviderToolCall> toolCalls,
        Map<String, Object> usage,
        Map<String, Object> providerState) {
}
