package com.wangfugui.notebookagent.agent;

import com.wangfugui.notebookagent.config.AppProperties;
import com.wangfugui.notebookagent.ingestion.DeleteService;
import com.wangfugui.notebookagent.ingestion.SourceService;
import com.wangfugui.notebookagent.mcp.McpClientManager;
import com.wangfugui.notebookagent.providers.OpenAlexProvider;
import com.wangfugui.notebookagent.repository.ChunkRepository;
import com.wangfugui.notebookagent.repository.DocumentRepository;
import com.wangfugui.notebookagent.repository.DocumentVersionRepository;
import com.wangfugui.notebookagent.repository.ExternalPaperResultRepository;
import com.wangfugui.notebookagent.repository.SectionRepository;
import com.wangfugui.notebookagent.repository.TaskItemRepository;
import com.wangfugui.notebookagent.retrieval.RetrievalService;
import com.wangfugui.notebookagent.skills.SkillCatalog;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ToolContext {
    private String runId;
    private String notebookId;
    private String userId;
    private List<String> allowedDocumentIds;
    private AgentState state;
    private AppProperties props;
    private DocumentRepository documents;
    private DocumentVersionRepository versions;
    private ChunkRepository chunks;
    private SectionRepository sections;
    private TaskItemRepository taskItems;
    private ExternalPaperResultRepository externalPapers;
    private OpenAlexProvider openalex;
    private SkillCatalog skills;
    private McpClientManager mcp;
    private ToolRegistry registry;
    private RetrievalService retrieval;
    private SourceService sourceService;
    private DeleteService deleteService;
}
