package com.wangfugui.notebookagent.ingestion;

import com.wangfugui.notebookagent.config.AppProperties;
import com.wangfugui.notebookagent.core.Json;
import com.wangfugui.notebookagent.domain.Chunk;
import com.wangfugui.notebookagent.domain.Document;
import com.wangfugui.notebookagent.domain.DocumentBlock;
import com.wangfugui.notebookagent.domain.DocumentProfile;
import com.wangfugui.notebookagent.domain.DocumentVersion;
import com.wangfugui.notebookagent.domain.IngestionJob;
import com.wangfugui.notebookagent.domain.Section;
import com.wangfugui.notebookagent.providers.DeepSeekProvider;
import com.wangfugui.notebookagent.providers.SiliconFlowProvider;
import com.wangfugui.notebookagent.queue.TaskQueue;
import com.wangfugui.notebookagent.repository.ChunkRepository;
import com.wangfugui.notebookagent.repository.DocumentBlockRepository;
import com.wangfugui.notebookagent.repository.DocumentProfileRepository;
import com.wangfugui.notebookagent.repository.DocumentRepository;
import com.wangfugui.notebookagent.repository.DocumentVersionRepository;
import com.wangfugui.notebookagent.repository.IngestionJobRepository;
import com.wangfugui.notebookagent.repository.SectionRepository;
import com.wangfugui.notebookagent.retrieval.MilvusStore;
import com.wangfugui.notebookagent.retrieval.Neo4jStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.wangfugui.notebookagent.queue.TaskLease;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Document ingestion: parse -> sections/blocks/chunks -> profile -> embeddings -> Milvus, then graph. */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    @Autowired private PlatformTransactionManager transactionManager;
    private final AppProperties props;
    private final Parser parser = new Parser();
    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final SectionRepository sections;
    private final DocumentBlockRepository blocks;
    private final ChunkRepository chunks;
    private final DocumentProfileRepository profiles;
    private final IngestionJobRepository jobs;
    private final TaskQueue tasks;

    public IngestionService(AppProperties props, DocumentRepository documents, DocumentVersionRepository versions,
                            SectionRepository sections, DocumentBlockRepository blocks, ChunkRepository chunks,
                            DocumentProfileRepository profiles, IngestionJobRepository jobs, TaskQueue tasks) {
        this.props = props;
        this.documents = documents;
        this.versions = versions;
        this.sections = sections;
        this.blocks = blocks;
        this.chunks = chunks;
        this.profiles = profiles;
        this.jobs = jobs;
        this.tasks = tasks;
    }

    public void ingestDocument(String versionId) {
        DocumentVersion version = versions.findById(versionId).orElse(null);
        if (version == null) {
            return;
        }
        if ("ready".equals(version.getStatus())) {
            return;
        }
        Document document = documents.findById(version.getDocumentId()).orElse(null);
        IngestionJob job = jobs.findByDocumentVersionId(versionId).orElse(null);
        if (document == null || job == null) {
            return;
        }
        try {
            TaskLease.check();
            version.setStatus("parsing");
            job.setStatus("running");
            job.setProgress(5);
            versions.save(version);
            jobs.save(job);

            byte[] content = Files.readAllBytes(Path.of(version.getStoragePath()));
            String suffix = extension(version.getStoragePath());
            Parsed.Document parsed = parser.parse(content, document.getMediaType(), suffix);

            List<Parsed.SectionDraft> sectionDrafts = Chunker.detectSections(parsed);
            List<Parsed.ChunkDraft> chunkDrafts = Chunker.chunkDocument(parsed);

            version.setFullText(parsed.getText());
            version.setNormalizedMarkdown(parsed.getMarkdown());
            version.setParserName(Parser.PARSER_NAME);
            version.setParserVersion(Parser.PARSER_VERSION);
            versions.save(version);

            TaskLease.check();
            blocks.deleteByDocumentVersionId(versionId);
            chunks.deleteByDocumentVersionId(versionId);
            sections.deleteByDocumentVersionId(versionId);

            List<Section> savedSections = new ArrayList<>();
            for (Parsed.SectionDraft draft : sectionDrafts) {
                Section section = new Section();
                section.setDocumentVersionId(versionId);
                section.setTitle(draft.getTitle());
                section.setOrdinal(draft.getOrdinal());
                section.setPageStart(draft.getPageStart());
                section.setPageEnd(draft.getPageEnd());
                savedSections.add(sections.save(section));
            }
            List<DocumentBlock> savedBlocks = new ArrayList<>();
            for (Parsed.Block block : parsed.getBlocks()) {
                DocumentBlock row = new DocumentBlock();
                row.setDocumentVersionId(versionId);
                row.setSectionId(sectionFor(savedSections, sectionDrafts, midpoint(block.getCharStart(), block.getCharEnd())));
                row.setOrdinal(savedBlocks.size());
                row.setBlockType(block.getType());
                row.setText(block.getText());
                row.setMarkdown(block.getMarkdown());
                row.setPageNumber(block.getPage());
                row.setBbox(Json.write(block.getBbox()));
                row.setCharStart(block.getCharStart());
                row.setCharEnd(block.getCharEnd());
                savedBlocks.add(blocks.save(row));
            }
            List<Chunk> savedChunks = new ArrayList<>();
            for (Parsed.ChunkDraft draft : chunkDrafts) {
                Chunk chunk = new Chunk();
                chunk.setDocumentVersionId(versionId);
                chunk.setSectionId(sectionFor(savedSections, sectionDrafts, midpoint(draft.getCharStart(), draft.getCharEnd())));
                chunk.setOrdinal(draft.getOrdinal());
                chunk.setPageStart(draft.getPageStart());
                chunk.setPageEnd(draft.getPageEnd());
                chunk.setCharStart(draft.getCharStart());
                chunk.setCharEnd(draft.getCharEnd());
                chunk.setContent(draft.getContent());
                chunk.setContentHash(draft.getContentHash());
                chunk.setChunkType(draft.getChunkType());
                chunk.setBlockIds(Json.write(overlappingBlockIds(savedBlocks, draft.getCharStart(), draft.getCharEnd())));
                chunk.setActive(true);
                savedChunks.add(chunks.save(chunk));
            }
            job.setProgress(20);
            jobs.save(job);

            TaskLease.check();
            DocumentProfile profile = buildProfile(document, version, parsed.getText());
            job.setProgress(40);
            jobs.save(job);

            Map<String, float[]> vectors = embedChunks(savedChunks, profile, version);
            TaskLease.check();
            writeMilvus(document, version, savedChunks, profile, vectors);
            TaskLease.check();
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            version.setStatus("ready");
            version.setGraphStatus("pending");
            version.setErrorMessage(null);
            versions.save(version);
            document.setActiveVersionId(version.getId());
            documents.save(document);
            job.setStatus("completed");
            job.setProgress(100);
            job.setErrorMessage(null);
            jobs.save(job);

            Map<String, Object> payload = Map.of("document_version_id", versionId);
            tasks.enqueue("build_graph_index", payload, "graph:" + versionId, 1);
            });
        } catch (Exception e) {
            TaskLease.check();
            log.warn("ingestion failed for version {}: {}", versionId, e.getMessage());
            version.setStatus("failed");
            version.setErrorMessage(String.valueOf(e.getMessage()));
            versions.save(version);
            job.setStatus("failed");
            job.setErrorMessage(String.valueOf(e.getMessage()));
            jobs.save(job);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private DocumentProfile buildProfile(Document document, DocumentVersion version, String fullText) {
        DeepSeekProvider provider = (props.getDeepseek().getApiKey() == null || props.getDeepseek().getApiKey().isBlank())
                ? null : new DeepSeekProvider(props);
        Map<String, Object> profile = ProfileExtractor.build(document.getTitle(), fullText, provider,
                props.effectiveInputBudget());
        profiles.deleteByDocumentVersionId(version.getId());
        DocumentProfile row = new DocumentProfile();
        row.setDocumentVersionId(version.getId());
        row.setDocumentId(document.getId());
        row.setNotebookId(document.getNotebookId());
        row.setAuthors(Json.write(profile.get("authors")));
        row.setYear(profile.get("year") instanceof Number n ? n.intValue() : null);
        row.setKeywords(Json.write(profile.get("keywords")));
        String abstractText = profile.get("abstract") == null ? null : String.valueOf(profile.get("abstract"));
        row.setAbstractText(abstractText);
        row.setAbstractSource(String.valueOf(profile.getOrDefault("abstract_source", "missing")));
        row.setRegion(profile.get("region") == null ? null : String.valueOf(profile.get("region")));
        row.setTimeRange(profile.get("time_range") == null ? null : String.valueOf(profile.get("time_range")));
        row.setOneSentence(String.valueOf(profile.getOrDefault("one_sentence", "")));
        row.setDataAndMethod(String.valueOf(profile.getOrDefault("data_and_method", "")));
        row.setResultsConclusion(String.valueOf(profile.getOrDefault("results_conclusion", "")));
        row.setContributionLimitations(String.valueOf(profile.getOrDefault("contribution_limitations", "")));
        row.setProfileText(ProfileExtractor.profileText(profile).replaceFirst("标题：.*\n?", ""));
        row.setStatus("ready");
        row.setExtractorModel(String.valueOf(profile.getOrDefault("extractor_model", "heuristic")));
        return profiles.save(row);
    }

    private Map<String, float[]> embedChunks(List<Chunk> chunks, DocumentProfile profile, DocumentVersion version) {
        SiliconFlowProvider provider = new SiliconFlowProvider(props);
        Map<String, float[]> vectors = new LinkedHashMap<>();
        for (int i = 0; i < chunks.size(); i += 32) {
            List<Chunk> batch = chunks.subList(i, Math.min(i + 32, chunks.size()));
            List<String> texts = batch.stream().map(Chunk::getContent).toList();
            List<float[]> embedded = provider.embed(texts);
            for (int j = 0; j < batch.size() && j < embedded.size(); j++) {
                vectors.put(batch.get(j).getId(), embedded.get(j));
            }
        }
        if (profile.getProfileText() != null && !profile.getProfileText().isBlank()) {
            List<float[]> embedded = provider.embed(List.of(profile.getProfileText()));
            if (!embedded.isEmpty()) {
                vectors.put("doc:" + version.getId(), embedded.get(0));
            }
        }
        return vectors;
    }

    private void writeMilvus(Document document, DocumentVersion version, List<Chunk> chunks,
                             DocumentProfile profile, Map<String, float[]> vectors) {
        MilvusStore milvus = new MilvusStore(props);
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Chunk chunk : chunks) {
                float[] vector = vectors.get(chunk.getId());
                if (vector == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("chunk_id", chunk.getId());
                row.put("notebook_id", document.getNotebookId());
                row.put("document_id", document.getId());
                row.put("document_version_id", version.getId());
                row.put("section_id", chunk.getSectionId() == null ? "" : chunk.getSectionId());
                row.put("ordinal", chunk.getOrdinal());
                row.put("title", document.getTitle().substring(0, Math.min(document.getTitle().length(), 1024)));
                row.put("language", version.getLanguage());
                row.put("page_start", chunk.getPageStart() == null ? 0 : chunk.getPageStart());
                row.put("page_end", chunk.getPageEnd() == null ? 0 : chunk.getPageEnd());
                row.put("chunk_type", chunk.getChunkType());
                row.put("block_ids", Json.readList(chunk.getBlockIds()));
                row.put("content", chunk.getContent());
                row.put("dense", floats(vector));
                row.put("is_active", true);
                rows.add(row);
            }
            milvus.upsertChunks(rows);
            if (profile.getProfileText() != null && !profile.getProfileText().isBlank()) {
                float[] vector = vectors.get("doc:" + version.getId());
                if (vector != null) {
                    Map<String, Object> docRow = new LinkedHashMap<>();
                    docRow.put("profile_id", "doc:" + version.getId());
                    docRow.put("notebook_id", document.getNotebookId());
                    docRow.put("document_id", document.getId());
                    docRow.put("document_version_id", version.getId());
                    docRow.put("title", document.getTitle().substring(0, Math.min(document.getTitle().length(), 1024)));
                    docRow.put("language", version.getLanguage());
                    docRow.put("is_active", true);
                    docRow.put("text", profile.getProfileText());
                    docRow.put("dense", floats(vector));
                    milvus.upsertDocuments(List.of(docRow));
                }
            }
        } finally {
            milvus.close();
        }
    }

    public void buildGraphIndex(String versionId) {
        DocumentVersion version = versions.findById(versionId).orElse(null);
        if (version == null) {
            return;
        }
        Document document = documents.findById(version.getDocumentId()).orElse(null);
        if (document == null) {
            return;
        }
        if (props.getDeepseek().getApiKey() == null || props.getDeepseek().getApiKey().isBlank()) {
            version.setGraphStatus("skipped");
            versions.save(version);
            return;
        }
        version.setGraphStatus("building");
        versions.save(version);
        Neo4jStore neo4j = new Neo4jStore(props);
        DeepSeekProvider provider = new DeepSeekProvider(props);
        try {
            if (!neo4j.verify()) {
                throw new IllegalStateException("neo4j unavailable");
            }
            neo4j.createConstraints();
            List<Chunk> chunkList = chunks.findByDocumentVersionIdAndIsActiveTrueOrderByOrdinal(versionId);
            Map<String, String> sectionTitles = new LinkedHashMap<>();
            for (Section section : sections.findByDocumentVersionIdOrderByOrdinal(versionId)) {
                sectionTitles.put(section.getId(), section.getTitle());
            }
            Map<String, List<Chunk>> groups = new LinkedHashMap<>();
            for (Chunk chunk : chunkList) {
                String key = chunk.getSectionId() == null ? "document" : chunk.getSectionId();
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(chunk);
            }
            for (Map.Entry<String, List<Chunk>> entry : groups.entrySet()) {
                TaskLease.check();
                String sectionId = entry.getKey();
                String sectionTitle = sectionTitles.getOrDefault(sectionId, "正文");
                List<Chunk> groupChunks = entry.getValue();
                for (int i = 0; i < groupChunks.size(); i += 8) {
                    List<Chunk> batch = groupChunks.subList(i, Math.min(i + 8, groupChunks.size()));
                    List<String> segments = batch.stream()
                            .map(c -> c.getContent().substring(0, Math.min(c.getContent().length(), 4000))).toList();
                    List<GraphExtractor.GraphResult> results =
                            GraphExtractor.extractSection(sectionTitle, segments, provider);
                    for (int j = 0; j < batch.size() && j < results.size(); j++) {
                        Chunk chunk = batch.get(j);
                        GraphExtractor.GraphResult result = results.get(j);
                        if (result.entities().isEmpty()) {
                            continue;
                        }
                        Map<String, Object> section = new LinkedHashMap<>();
                        section.put("id", sectionId);
                        section.put("title", sectionTitle);
                        neo4j.upsertChunk(document.getNotebookId(), version.getId(), document.getId(),
                                document.getTitle(), chunk.getId(), section, result.entities(), result.relations());
                    }
                }
            }
            TaskLease.check();
            version.setGraphStatus("ready");
            versions.save(version);
        } catch (Exception e) {
            TaskLease.check();
            log.warn("graph build failed for version {}: {}", versionId, e.getMessage());
            version.setGraphStatus("failed");
            versions.save(version);
        } finally {
            neo4j.close();
        }
    }

    private static List<String> overlappingBlockIds(List<DocumentBlock> blocks, int start, int end) {
        List<String> ids = new ArrayList<>();
        for (DocumentBlock block : blocks) {
            if (block.getCharStart() < end && block.getCharEnd() > start) {
                ids.add(block.getId());
            }
        }
        return ids;
    }

    private static String sectionFor(List<Section> saved, List<Parsed.SectionDraft> drafts, int position) {
        for (int i = 0; i < drafts.size() && i < saved.size(); i++) {
            Parsed.SectionDraft draft = drafts.get(i);
            if (position >= draft.getCharStart() && position < draft.getCharEnd()) {
                return saved.get(i).getId();
            }
        }
        return saved.isEmpty() ? null : saved.get(saved.size() - 1).getId();
    }

    private static int midpoint(int start, int end) {
        return start + (end - start) / 2;
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot);
    }

    private static List<Float> floats(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add(v);
        }
        return list;
    }
}
