package com.wangfugui.notebookagent.ingestion;

import com.wangfugui.notebookagent.queue.TaskHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class BuildGraphIndexTaskHandler implements TaskHandler {

    private final IngestionService ingestion;

    public BuildGraphIndexTaskHandler(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @Override
    public String type() {
        return "build_graph_index";
    }

    @Override
    public void handle(String taskId, Map<String, Object> payload) {
        Object versionId = payload.get("document_version_id");
        if (versionId != null) {
            ingestion.buildGraphIndex(String.valueOf(versionId));
        }
    }
}
