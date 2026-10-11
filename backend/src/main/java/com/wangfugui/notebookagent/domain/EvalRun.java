package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "eval_runs", indexes = {
        @Index(name = "ix_eval_runs_user", columnList = "user_id"),
        @Index(name = "ix_eval_runs_status", columnList = "status")})
@Getter
@Setter
public class EvalRun extends BaseEntity {
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;
    @Column(name = "notebook_id", nullable = false, length = 36)
    private String notebookId;
    @Column(name = "dataset_id", length = 36)
    private String datasetId;
    @Column(name = "dataset_name", nullable = false, length = 255)
    private String datasetName = "";
    @Column(nullable = false, length = 24)
    private String status = "pending";
    @Column(name = "total_cases", nullable = false)
    private int totalCases;
    @Column(name = "completed_cases", nullable = false)
    private int completedCases;
    @Column(name = "passed_cases", nullable = false)
    private int passedCases;
    @Column(name = "current_case_key", length = 120)
    private String currentCaseKey;
    @Column(name = "current_agent_run_id", length = 36)
    private String currentAgentRunId;
    @Column(name = "current_case_result_id", length = 36)
    private String currentCaseResultId;
    @Column(name = "score_config", nullable = false, columnDefinition = "json")
    private String scoreConfig = "{}";
    @Column(nullable = false, columnDefinition = "json")
    private String metrics = "{}";
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;
}
