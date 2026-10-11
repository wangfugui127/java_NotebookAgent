package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "tool_calls", indexes = {
        @Index(name = "ix_tool_calls_run", columnList = "run_id"),
        @Index(name = "ix_tool_calls_provider", columnList = "provider_call_id")})
@Getter
@Setter
public class ToolCall extends BaseEntity {
    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;
    @Column(name = "provider_call_id", nullable = false, length = 255)
    private String providerCallId;
    @Column(name = "tool_name", nullable = false, length = 255)
    private String toolName;
    @Column(nullable = false, columnDefinition = "json")
    private String arguments = "{}";
    @Column(name = "arguments_hash", nullable = false, length = 64)
    private String argumentsHash;
    @Column(nullable = false, length = 24)
    private String risk = "read";
    @Column(nullable = false, length = 32)
    private String status = "pending";
    @Column(columnDefinition = "json")
    private String result;
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;
}
