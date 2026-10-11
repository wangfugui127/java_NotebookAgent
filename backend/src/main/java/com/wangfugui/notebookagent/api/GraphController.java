package com.wangfugui.notebookagent.api;

import com.wangfugui.notebookagent.config.AppProperties;
import com.wangfugui.notebookagent.core.ApiException;
import com.wangfugui.notebookagent.core.CurrentUser;
import com.wangfugui.notebookagent.domain.User;
import com.wangfugui.notebookagent.providers.DeepSeekProvider;
import com.wangfugui.notebookagent.providers.SiliconFlowProvider;
import com.wangfugui.notebookagent.agent.RagRouter;
import com.wangfugui.notebookagent.repository.ChunkRepository;
import com.wangfugui.notebookagent.repository.DocumentVersionRepository;
import com.wangfugui.notebookagent.repository.NotebookRepository;
import com.wangfugui.notebookagent.repository.SectionRepository;
import com.wangfugui.notebookagent.retrieval.MilvusStore;
import com.wangfugui.notebookagent.retrieval.Neo4jStore;
import com.wangfugui.notebookagent.retrieval.RetrievalService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/notebooks/{notebookId}/graph")
public class GraphController {

    @org.springframework.beans.factory.annotation.Autowired private com.wangfugui.notebookagent.repository.DocumentRepository documents;
    private final NotebookRepository notebooks;
    private final AppProperties props;
    private final ChunkRepository chunks;
    private final SectionRepository sections;
    private final DocumentVersionRepository versions;

    public GraphController(NotebookRepository notebooks, AppProperties props, ChunkRepository chunks,
                           SectionRepository sections, DocumentVersionRepository versions) {
        this.notebooks = notebooks;
        this.props = props;
        this.chunks = chunks;
        this.sections = sections;
        this.versions = versions;
    }

    @GetMapping("/experiments")
    public Map<String, Object> experiments(@PathVariable String notebookId,
                                           @RequestParam(value = "q", defaultValue = "") String q,
                                           @RequestParam(value = "limit", defaultValue = "10") int limit,
                                           @CurrentUser User user) {
        notebooks.findByIdAndOwnerId(notebookId, user.getId())
                .orElseThrow(() -> ApiException.notFound("notebook not found"));
        try (RetrievalService service = new RetrievalService(new MilvusStore(props), new Neo4jStore(props),
                new SiliconFlowProvider(props), new RagRouter(new DeepSeekProvider(props)), chunks, sections, versions, documents)) {
            return service.graphExperiments(notebookId, q, Math.min(Math.max(limit, 1), 50));
        }
    }
}
