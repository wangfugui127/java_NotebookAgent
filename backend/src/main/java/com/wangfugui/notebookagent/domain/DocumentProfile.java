package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "document_profiles", indexes = @Index(name = "ix_profile_document", columnList = "document_id"))
@Getter
@Setter
public class DocumentProfile extends BaseEntity {
    @Column(name = "document_version_id", nullable = false, unique = true, length = 36)
    private String documentVersionId;
    @Column(name = "document_id", nullable = false, length = 36)
    private String documentId;
    @Column(name = "notebook_id", nullable = false, length = 36)
    private String notebookId;
    @Column(nullable = false, columnDefinition = "json")
    private String authors = "[]";
    @Column
    private Integer year;
    @Column(nullable = false, columnDefinition = "json")
    private String keywords = "[]";
    @Column(name = "abstract", columnDefinition = "longtext")
    private String abstractText;
    @Column(name = "abstract_source", nullable = false, length = 16)
    private String abstractSource = "missing";
    @Column(length = 512)
    private String region;
    @Column(name = "time_range", length = 255)
    private String timeRange;
    @Column(name = "metadata_json", nullable = false, columnDefinition = "json")
    private String metadataJson = "{}";
    @Column(name = "one_sentence", nullable = false, columnDefinition = "longtext")
    private String oneSentence = "";
    @Column(name = "data_and_method", nullable = false, columnDefinition = "longtext")
    private String dataAndMethod = "";
    @Column(name = "results_conclusion", nullable = false, columnDefinition = "longtext")
    private String resultsConclusion = "";
    @Column(name = "contribution_limitations", nullable = false, columnDefinition = "longtext")
    private String contributionLimitations = "";
    @Column(name = "profile_text", nullable = false, columnDefinition = "longtext")
    private String profileText = "";
    @Column(nullable = false, length = 24)
    private String status = "pending";
    @Column(name = "extractor_model", nullable = false, length = 120)
    private String extractorModel = "";
    @Column(name = "extractor_version", nullable = false, length = 24)
    private String extractorVersion = "1";
}
