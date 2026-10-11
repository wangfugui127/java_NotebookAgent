package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "eval_cases", indexes = @Index(name = "ix_eval_cases_dataset", columnList = "dataset_id"))
@Getter
@Setter
public class EvalCase extends BaseEntity {
    @Column(name = "dataset_id", nullable = false, length = 36)
    private String datasetId;
    @Column(name = "case_key", nullable = false, length = 120)
    private String caseKey;
    @Column(nullable = false, columnDefinition = "longtext")
    private String question;
    @Column(name = "expected_tools", nullable = false, columnDefinition = "json")
    private String expectedTools = "[]";
    @Column(name = "expected_source_titles", nullable = false, columnDefinition = "json")
    private String expectedSourceTitles = "[]";
    @Column(name = "required_keywords", nullable = false, columnDefinition = "json")
    private String requiredKeywords = "[]";
    @Column(nullable = false)
    private int ordinal;
}
