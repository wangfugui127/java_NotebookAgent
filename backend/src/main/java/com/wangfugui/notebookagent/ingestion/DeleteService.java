package com.wangfugui.notebookagent.ingestion;

import com.wangfugui.notebookagent.config.AppProperties;
import com.wangfugui.notebookagent.domain.Chunk;
import com.wangfugui.notebookagent.domain.Document;
import com.wangfugui.notebookagent.domain.DocumentVersion;
import com.wangfugui.notebookagent.domain.Evidence;
import com.wangfugui.notebookagent.repository.ChunkRepository;
import com.wangfugui.notebookagent.repository.CitationRepository;
import com.wangfugui.notebookagent.repository.DocumentRepository;
import com.wangfugui.notebookagent.repository.DocumentVersionRepository;
import com.wangfugui.notebookagent.repository.EvidenceRepository;
import com.wangfugui.notebookagent.retrieval.MilvusStore;
import com.wangfugui.notebookagent.retrieval.Neo4jStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Deletes a document and its derived rows/index entries, preserving the original order:
 * Citation/Evidence removal, then Neo4j delete BEFORE the DB commit, then Milvus delete
 * AFTER the commit, then the on-disk files. External failures are non-fatal.
 */
@Service
public class DeleteService {

    private static final Logger log = LoggerFactory.getLogger(DeleteService.class);

    private final AppProperties props;
    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final ChunkRepository chunks;
    private final EvidenceRepository evidence;
    private final CitationRepository citations;

    public DeleteService(AppProperties props, DocumentRepository documents, DocumentVersionRepository versions,
                         ChunkRepository chunks, EvidenceRepository evidence, CitationRepository citations) {
        this.props = props;
        this.documents = documents;
        this.versions = versions;
        this.chunks = chunks;
        this.evidence = evidence;
        this.citations = citations;
    }

    @Transactional
    public void deleteSource(Document document) {
        List<DocumentVersion> versionList = versions.findByDocumentId(document.getId());
        Neo4jStore neo4j = null;
        try { neo4j = new Neo4jStore(props); } catch (Exception e) { log.debug("Neo4j unavailable during deletion"); }
        for (DocumentVersion version : versionList) {
            List<Chunk> chunkList = chunks.findByDocumentVersionIdAndIsActiveTrueOrderByOrdinal(version.getId());
            List<String> chunkIds = chunkList.stream().map(Chunk::getId).toList();
            if (!chunkIds.isEmpty()) {
                List<Evidence> evidenceRows = evidence.findByChunkIdIn(chunkIds);
                List<String> evidenceIds = evidenceRows.stream().map(Evidence::getId).toList();
                if (!evidenceIds.isEmpty()) {
                    citations.deleteByEvidenceIdIn(evidenceIds);
                    evidence.deleteAllById(evidenceIds);
                }
            }
            try {
                if (neo4j != null) neo4j.deleteDocumentVersion(version.getId());
            } catch (Exception e) {
                log.debug("neo4j delete failed (non-fatal): {}", e.getMessage());
            }
        }
        try { if (neo4j != null) neo4j.close(); } catch (Exception ignored) {}
        document.setActiveVersionId(null);
        documents.saveAndFlush(document);
        documents.deleteById(document.getId());
        // External cleanup runs only once the DB deletion actually commits.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                MilvusStore milvus = null;
                try {
                    milvus = new MilvusStore(props); milvus.deleteByDocument(document.getId());
                } catch (Exception e) { log.debug("Milvus cleanup failed (non-fatal): {}", e.getMessage()); }
                finally { try { if (milvus != null) milvus.close(); } catch (Exception ignored) {} }
                Path root = Path.of(props.getUploadDir()).toAbsolutePath().normalize();
                Path dir = root.resolve(document.getNotebookId()).resolve(document.getId()).normalize();
                if (!dir.startsWith(root) || dir.equals(root)) throw new IllegalStateException("invalid upload cleanup path");
                try { deleteRecursively(dir); } catch (Exception e) { log.debug("file cleanup failed (non-fatal): {}", e.getMessage()); }
            }
        });
    }

    private static void deleteRecursively(Path path) throws java.io.IOException {
        if (!java.nio.file.Files.exists(path)) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        try (var stream = java.nio.file.Files.walk(path)) { stream.forEach(paths::add); }
        for (int i = paths.size() - 1; i >= 0; i--) {
            java.nio.file.Files.deleteIfExists(paths.get(i));
        }
    }
}
