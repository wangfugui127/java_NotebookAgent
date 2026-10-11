package com.wangfugui.notebookagent.ingestion;

import com.wangfugui.notebookagent.config.AppProperties;
import com.wangfugui.notebookagent.domain.Document;
import com.wangfugui.notebookagent.domain.DocumentVersion;
import com.wangfugui.notebookagent.domain.IngestionJob;
import com.wangfugui.notebookagent.queue.TaskQueue;
import com.wangfugui.notebookagent.repository.DocumentRepository;
import com.wangfugui.notebookagent.repository.DocumentVersionRepository;
import com.wangfugui.notebookagent.repository.IngestionJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Creates document sources and enqueues ingestion (shared by upload and tools). */
@Service
public class SourceService {

    private static final Logger log = LoggerFactory.getLogger(SourceService.class);

    private final AppProperties props;
    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final IngestionJobRepository jobs;
    private final TaskQueue tasks;

    public SourceService(AppProperties props, DocumentRepository documents, DocumentVersionRepository versions,
                         IngestionJobRepository jobs, TaskQueue tasks) {
        this.props = props;
        this.documents = documents;
        this.versions = versions;
        this.jobs = jobs;
        this.tasks = tasks;
    }

    public record Created(String documentId, String versionId, String jobId) {
    }

    @Transactional
    public Created createSource(String notebookId, String title, String mediaType, byte[] content, String suffix) {
        Document document = new Document();
        document.setNotebookId(notebookId);
        String safeTitle = (title == null || title.isBlank()) ? "untitled" : title;
        document.setTitle(safeTitle.substring(0, Math.min(safeTitle.length(), 512)));
        document.setMediaType(mediaType == null ? "application/octet-stream" : mediaType);
        documents.save(document);

        int versionNumber = (int) versions.countByDocumentId(document.getId()) + 1;
        String contentHash = sha256(content);
        Path dir = Path.of(props.getUploadDir()).resolve(notebookId).resolve(document.getId());
        try {
            Files.createDirectories(dir);
            String safeSuffix = suffix != null && suffix.matches("\\.[A-Za-z0-9]{1,12}") ? suffix : "";
            Path target = dir.resolve("v" + versionNumber + safeSuffix);
            Files.write(target, content);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        try { Files.deleteIfExists(target); Files.deleteIfExists(dir); } catch (Exception ignored) {}
                    }
                }
            });
            DocumentVersion version = new DocumentVersion();
            version.setDocumentId(document.getId());
            version.setVersionNumber(versionNumber);
            version.setStoragePath(target.toString());
            version.setContentHash(contentHash);
            versions.save(version);

            IngestionJob job = new IngestionJob();
            job.setDocumentVersionId(version.getId());
            job.setIdempotencyKey("ingest:" + version.getId() + ":" + contentHash);
            jobs.save(job);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("document_version_id", version.getId());
            tasks.enqueue("ingest_document", payload, "ingest:" + version.getId() + ":" + contentHash, 4);
            return new Created(document.getId(), version.getId(), job.getId());
        } catch (Exception e) {
            throw new RuntimeException("failed to store source: " + e.getMessage(), e);
        }
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
