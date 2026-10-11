package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "external_paper_results", indexes = @Index(name = "ix_external_run", columnList = "run_id"))
@Getter
@Setter
public class ExternalPaperResult extends BaseEntity {
    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;
    @Column(nullable = false, length = 40)
    private String provider;
    @Column(name = "provider_paper_id", nullable = false, length = 255)
    private String providerPaperId;
    @Column(nullable = false, columnDefinition = "json")
    private String data = "{}";
}
