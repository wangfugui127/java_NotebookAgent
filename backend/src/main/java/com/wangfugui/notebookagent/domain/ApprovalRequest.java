package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "approval_requests", indexes = @Index(name = "ix_approvals_run", columnList = "run_id"))
@Getter
@Setter
public class ApprovalRequest extends BaseEntity {
    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;
    @Column(name = "tool_call_id", nullable = false, length = 36)
    private String toolCallId;
    @Column(nullable = false, length = 24)
    private String status = "pending";
    @Column(nullable = false, columnDefinition = "text")
    private String reason;
    @Column(nullable = false, length = 16)
    private String mode = "confirm";
    @Column(name = "tool_risk", nullable = false, length = 16)
    private String toolRisk = "write";
    @Column(name = "resolved_by", length = 36)
    private String resolvedBy;
}
