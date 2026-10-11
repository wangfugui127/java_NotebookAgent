package com.wangfugui.notebookagent.retrieval;

import java.util.List;

public record GraphPlan(boolean useGraph, List<GraphEntity> entities, List<String> intents) {
    public static GraphPlan none() {
        return new GraphPlan(false, List.of(), List.of());
    }
}
