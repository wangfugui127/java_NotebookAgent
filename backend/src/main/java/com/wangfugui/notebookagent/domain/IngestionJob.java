package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "ingestion_jobs", indexes = @Index(name = "ix_ingestion_status", columnList = "status"))
@Getter
@Setter
public class IngestionJob extends BaseEntity {
    @Column(name = "document_version_id", nullable = false, unique = true, length = 36)
    private String documentVersionId;
    @Column(nullable = false, length = 40)
    private String status = "pending";
    @Column(nullable = false)
    private int progress;
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
    private String idempotencyKey;
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;
}
