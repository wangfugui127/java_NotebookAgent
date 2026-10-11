package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "document_versions", indexes = @Index(name = "ix_versions_document", columnList = "document_id"))
@Getter
@Setter
public class DocumentVersion extends BaseEntity {
    @Column(name = "document_id", nullable = false, length = 36)
    private String documentId;
    @Column(name = "version_number", nullable = false)
    private int versionNumber = 1;
    @Column(name = "storage_path", nullable = false, length = 1024)
    private String storagePath;
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;
    @Column(nullable = false, length = 24)
    private String language = "default";
    @Column(nullable = false, length = 40)
    private String status = "pending";
    @Column(name = "graph_status", nullable = false, length = 24)
    private String graphStatus = "pending";
    @Column(name = "full_text", columnDefinition = "longtext")
    private String fullText;
    @Column(name = "normalized_markdown", columnDefinition = "longtext")
    private String normalizedMarkdown;
    @Column(name = "parser_name", nullable = false, length = 64)
    private String parserName = "";
    @Column(name = "parser_version", nullable = false, length = 24)
    private String parserVersion = "1";
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;
}
