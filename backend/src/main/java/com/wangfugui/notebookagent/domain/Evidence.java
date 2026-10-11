package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "evidence", indexes = @Index(name = "ix_evidence_run", columnList = "run_id"))
@Getter
@Setter
public class Evidence extends BaseEntity {
    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;
    @Column(name = "chunk_id", length = 36)
    private String chunkId;
    @Column(name = "source_kind", nullable = false, length = 32)
    private String sourceKind;
    @Column(name = "source_id", nullable = false, length = 255)
    private String sourceId;
    @Column(nullable = false, columnDefinition = "longtext")
    private String text;
    @Column(name = "section_title", length = 512)
    private String sectionTitle;
    @Column(name = "page_start")
    private Integer pageStart;
    @Column(name = "page_end")
    private Integer pageEnd;
    @Column(name = "char_start")
    private Integer charStart;
    @Column(name = "char_end")
    private Integer charEnd;
    @Column(name = "block_ids", nullable = false, columnDefinition = "json")
    private String blockIds = "[]";
    @Column(name = "retrieval_sources", nullable = false, columnDefinition = "json")
    private String retrievalSources = "[]";
    @Column
    private Double score;
}
