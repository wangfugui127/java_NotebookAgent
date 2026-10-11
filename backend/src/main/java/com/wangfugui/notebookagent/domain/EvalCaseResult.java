package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "eval_case_results", indexes = @Index(name = "ix_eval_results_run", columnList = "eval_run_id"))
@Getter
@Setter
public class EvalCaseResult extends BaseEntity {
    @Column(name = "eval_run_id", nullable = false, length = 36)
    private String evalRunId;
    @Column(name = "case_key", nullable = false, length = 120)
    private String caseKey = "";
    @Column(nullable = false)
    private int ordinal;
    @Column(nullable = false, columnDefinition = "longtext")
    private String question;
    @Column(nullable = false, length = 24)
    private String status = "pending";
    @Column(name = "expected_tools", nullable = false, columnDefinition = "json")
    private String expectedTools = "[]";
    @Column(name = "actual_tools", nullable = false, columnDefinition = "json")
    private String actualTools = "[]";
    @Column(name = "expected_source_titles", nullable = false, columnDefinition = "json")
    private String expectedSourceTitles = "[]";
    @Column(name = "matched_sources", nullable = false, columnDefinition = "json")
    private String matchedSources = "[]";
    @Column(name = "missing_sources", nullable = false, columnDefinition = "json")
    private String missingSources = "[]";
    @Column(name = "required_keywords", nullable = false, columnDefinition = "json")
    private String requiredKeywords = "[]";
    @Column(name = "matched_keywords", nullable = false, columnDefinition = "json")
    private String matchedKeywords = "[]";
    @Column(columnDefinition = "longtext")
    private String answer;
    @Column(name = "route_score")
    private Double routeScore;
    @Column(name = "retrieval_score")
    private Double retrievalScore;
    @Column(name = "citation_score")
    private Double citationScore;
    @Column(name = "keyword_score")
    private Double keywordScore;
    @Column(name = "total_score")
    private Double totalScore;
    @Column(nullable = false)
    private boolean passed;
    @Column(name = "latency_ms")
    private Integer latencyMs;
    @Column(name = "agent_run_id", length = 36)
    private String agentRunId;
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;
    @Column(nullable = false, columnDefinition = "json")
    private String detail = "{}";
}
