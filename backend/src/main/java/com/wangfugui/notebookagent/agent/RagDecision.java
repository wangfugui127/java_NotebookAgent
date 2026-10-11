package com.wangfugui.notebookagent.agent;

import com.wangfugui.notebookagent.retrieval.GraphPlan;

public record RagDecision(String route, GraphPlan graph) {
    public static RagDecision uncertain() {
        return new RagDecision("uncertain", GraphPlan.none());
    }
}
