package com.wangfugui.notebookagent.retrieval;

import com.wangfugui.notebookagent.agent.RagDecision;
import com.wangfugui.notebookagent.agent.RagRouter;
import com.wangfugui.notebookagent.core.Json;
import com.wangfugui.notebookagent.domain.Chunk;
import com.wangfugui.notebookagent.domain.DocumentVersion;
import com.wangfugui.notebookagent.domain.Section;
import com.wangfugui.notebookagent.providers.SiliconFlowProvider;
import com.wangfugui.notebookagent.repository.ChunkRepository;
import com.wangfugui.notebookagent.repository.DocumentVersionRepository;
import com.wangfugui.notebookagent.repository.SectionRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Hybrid retrieval: document routing -> Milvus hybrid -> optional Neo4j graph -> RRF -> rerank. */
public class RetrievalService implements AutoCloseable {
    @Override public void close() { try { milvus.close(); } finally { neo4j.close(); } }

    private final MilvusStore milvus;
    private final Neo4jStore neo4j;
    private final SiliconFlowProvider siliconflow;
    private final RagRouter ragRouter;
    private final ChunkRepository chunks;
    private final SectionRepository sections;
    private final DocumentVersionRepository versions;
    private final com.wangfugui.notebookagent.repository.DocumentRepository documents;

    public RetrievalService(MilvusStore milvus, Neo4jStore neo4j, SiliconFlowProvider siliconflow,
                            RagRouter ragRouter, ChunkRepository chunks, SectionRepository sections,
                            DocumentVersionRepository versions, com.wangfugui.notebookagent.repository.DocumentRepository documents) {
        this.milvus = milvus;
        this.neo4j = neo4j;
        this.siliconflow = siliconflow;
        this.ragRouter = ragRouter;
        this.chunks = chunks;
        this.sections = sections;
        this.versions = versions;
        this.documents = documents;
    }

    public Map<String, Object> search(String notebookId, String query, List<String> documentIds,
                                      String mode, int topK, GraphPlan graphPlan) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean layeredUsed = false;
        boolean layeredDegraded = false;
        boolean graphDegraded = false;
        List<String> candidateDocuments = new ArrayList<>();
        Map<String, List<SearchHit>> resultSets = new LinkedHashMap<>();

        float[] vector = null;
        try {
            vector = siliconflow.embed(List.of(query)).get(0);
        } catch (RuntimeException e) {
            result.put("error", e.getMessage());
        }

        if (vector != null) {
            if (Set.of("layered", "auto", "comprehensive").contains(mode)) {
                try {
                    candidateDocuments = documentCandidates(query, vector, notebookId, documentIds, 10);
                } catch (RuntimeException e) {
                    // Document profiles are optional; keep full-notebook chunk retrieval available.
                    org.slf4j.LoggerFactory.getLogger(RetrievalService.class).warn("Document routing unavailable; using chunk retrieval", e);
                }
                layeredUsed = !candidateDocuments.isEmpty();
                if ("layered".equals(mode) && candidateDocuments.isEmpty()) {
                    layeredDegraded = true;
                }
            }
            resultSets.put("hybrid", enrich(milvus.hybridSearch(query, vector, notebookId, documentIds, null, 30), "hybrid"));
            if (layeredUsed) {
                resultSets.put("layered",
                        enrich(milvus.hybridSearch(query, vector, notebookId, candidateDocuments, null, 30), "layered"));
            }
        }

        GraphPlan plan = graphPlan;
        if (plan == null && ragRouter != null) {
            try {
                RagDecision decision = ragRouter.route(query);
                plan = decision.graph();
            } catch (RuntimeException e) {
                plan = GraphPlan.none();
            }
        }
        boolean graphAttempted = plan != null && plan.useGraph() && !plan.entities().isEmpty();
        boolean graphUsed = false;
        if (graphAttempted) {
            try {
                List<SearchHit> graphHits = graphHits(notebookId, plan);
                if (!graphHits.isEmpty()) {
                    resultSets.put("neo4j", graphHits);
                    graphUsed = true;
                }
            } catch (RuntimeException e) {
                graphDegraded = true;
            }
        }

        if (documentIds != null) for (List<SearchHit> hits : resultSets.values()) hits.removeIf(h -> !documentIds.contains(h.getDocumentId()));
        List<SearchHit> fused = Fusion.reciprocalRankFusion(resultSets, 60, 30);
        List<SearchHit> reranked = rerank(query, fused, topK);

        result.put("mode_requested", mode);
        result.put("mode_used", layeredUsed ? "layered" : "hybrid");
        result.put("layered_used", layeredUsed);
        result.put("layered_degraded", layeredDegraded);
        result.put("fallback_full_notebook", true);
        result.put("graph_attempted", graphAttempted);
        result.put("graph_used", graphUsed);
        result.put("graph_degraded", graphDegraded);
        result.put("items", reranked);
        return result;
    }

    private List<String> documentCandidates(String query, float[] vector, String notebookId,
                                            List<String> documentIds, int limit) {
        List<MilvusStore.MilvusHit> hits = milvus.searchDocuments(query, vector, notebookId, documentIds, limit);
        Set<String> docs = new LinkedHashSet<>();
        for (MilvusStore.MilvusHit hit : hits) {
            String versionId = profileVersionId(hit.id());
            if (versionId == null) {
                continue;
            }
            versions.findById(versionId).map(DocumentVersion::getDocumentId)
                    .filter(id -> documentIds == null || documentIds.contains(id)).ifPresent(docs::add);
        }
        List<String> result = new ArrayList<>(docs);
        return result;
    }

    private static String profileVersionId(String profileId) {
        if (profileId == null) {
            return null;
        }
        return profileId.startsWith("doc:") ? profileId.substring(4) : profileId;
    }

    private List<SearchHit> graphHits(String notebookId, GraphPlan plan) {
        if (!neo4j.verify()) {
            throw new IllegalStateException("neo4j unavailable");
        }
        List<String> ids = neo4j.searchEvidence(notebookId, plan.entities(), plan.intents(), 30);
        List<SearchHit> hits = new ArrayList<>();
        Map<String, Chunk> byId = new HashMap<>();
        for (Chunk chunk : chunks.findByIdInAndIsActiveTrue(ids)) {
            byId.put(chunk.getId(), chunk);
        }
        int rank = 0;
        for (String id : ids) {
            Chunk chunk = byId.get(id);
            if (chunk == null) {
                continue;
            }
            SearchHit hit = buildHit(chunk, 1.0 / (rank + 1));
            hit.setSources(new ArrayList<>(List.of("neo4j")));
            hits.add(hit);
            rank++;
        }
        return hits;
    }

    private List<SearchHit> enrich(List<MilvusStore.MilvusHit> hits, String source) {
        List<SearchHit> out = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (MilvusStore.MilvusHit hit : hits) {
            if (hit.id() != null) {
                ids.add(hit.id());
            }
        }
        Map<String, Chunk> byId = new HashMap<>();
        for (Chunk chunk : chunks.findByIdInAndIsActiveTrue(ids)) {
            byId.put(chunk.getId(), chunk);
        }
        for (MilvusStore.MilvusHit hit : hits) {
            Chunk chunk = byId.get(hit.id());
            if (chunk == null) {
                continue;
            }
            SearchHit searchHit = buildHit(chunk, hit.score());
            searchHit.setSources(new ArrayList<>(List.of(source)));
            out.add(searchHit);
        }
        return out;
    }

    private SearchHit buildHit(Chunk chunk, double score) {
        SearchHit hit = new SearchHit();
        hit.setChunkId(chunk.getId());
        hit.setText(chunk.getContent());
        hit.setScore(score);
        hit.setDocumentVersionId(chunk.getDocumentVersionId());
        hit.setSectionId(chunk.getSectionId());
        hit.setOrdinal(chunk.getOrdinal());
        hit.setCharStart(chunk.getCharStart());
        hit.setCharEnd(chunk.getCharEnd());
        hit.setPageStart(chunk.getPageStart());
        hit.setPageEnd(chunk.getPageEnd());
        hit.setChunkType(chunk.getChunkType());
        hit.setBlockIds(Json.readStringList(chunk.getBlockIds()));
        versions.findById(chunk.getDocumentVersionId()).ifPresent(v -> {
            hit.setDocumentId(v.getDocumentId());
            hit.setTitle(documents.findById(v.getDocumentId()).map(com.wangfugui.notebookagent.domain.Document::getTitle).orElse(v.getDocumentId()));
        });
        if (chunk.getSectionId() != null) {
            sections.findById(chunk.getSectionId()).ifPresent(s -> hit.setSectionTitle(s.getTitle()));
        }
        return hit;
    }

    private List<SearchHit> rerank(String query, List<SearchHit> fused, int topK) {
        if (fused.isEmpty()) {
            return fused;
        }
        try {
            List<String> docs = fused.stream().map(SearchHit::getText).toList();
            List<Map<String, Object>> ranked = siliconflow.rerank(query, docs, Math.min(topK, fused.size()));
            List<SearchHit> out = new ArrayList<>();
            for (Map<String, Object> entry : ranked) {
                Object idx = entry.get("index");
                if (!(idx instanceof Number n)) {
                    continue;
                }
                int index = n.intValue();
                if (index < 0 || index >= fused.size()) {
                    continue;
                }
                SearchHit hit = fused.get(index);
                Object relevance = entry.get("relevance_score");
                if (relevance instanceof Number r) {
                    hit.setScore(r.doubleValue());
                }
                out.add(hit);
            }
            return out.isEmpty() ? fused.stream().limit(topK).toList() : out;
        } catch (RuntimeException e) {
            return fused.stream().limit(topK).toList();
        }
    }

    public Map<String, Object> graphExperiments(String notebookId, String query, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!neo4j.verify()) {
            out.put("items", List.of());
            out.put("degraded", true);
            return out;
        }
        List<String> names = entityCandidates(query);
        List<Map<String, Object>> experiments = neo4j.searchExperiments(notebookId, names, limit);
        Set<String> chunkIds = new LinkedHashSet<>();
        for (Map<String, Object> experiment : experiments) {
            for (String key : List.of("methods", "datasets", "results", "metrics")) {
                if (experiment.get(key) instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> m && m.get("evidence") instanceof List<?> ev) {
                            for (Object id : ev) {
                                chunkIds.add(String.valueOf(id));
                            }
                        }
                    }
                }
            }
        }
        Map<String, Object> chunksMap = new LinkedHashMap<>();
        for (Chunk chunk : chunks.findByIdInAndIsActiveTrue(chunkIds)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chunk_id", chunk.getId());
            row.put("page_start", chunk.getPageStart());
            row.put("page_end", chunk.getPageEnd());
            row.put("chunk_type", chunk.getChunkType());
            row.put("block_ids", Json.readStringList(chunk.getBlockIds()));
            row.put("content", chunk.getContent());
            chunksMap.put(chunk.getId(), row);
        }
        out.put("items", experiments);
        out.put("chunks", chunksMap);
        out.put("degraded", false);
        return out;
    }

    private static final java.util.regex.Pattern CANDIDATE =
            java.util.regex.Pattern.compile("\\b[A-Z][A-Za-z0-9-]{2,}\\b|\"([^\"]{2,40})\"|“([^”]{2,40})”");

    static List<String> entityCandidates(String query) {
        List<String> out = new ArrayList<>();
        if (query == null) {
            return out;
        }
        java.util.regex.Matcher matcher = CANDIDATE.matcher(query);
        while (matcher.find() && out.size() < 12) {
            String value = matcher.group();
            value = value.replace("\"", "").replace("“", "").replace("”", "");
            if (!out.contains(value)) {
                out.add(value);
            }
        }
        return out;
    }
}
