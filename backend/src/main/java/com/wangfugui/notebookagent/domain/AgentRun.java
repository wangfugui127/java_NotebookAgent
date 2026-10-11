package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "agent_runs", indexes = {
        @Index(name = "ix_runs_conversation", columnList = "conversation_id"),
        @Index(name = "ix_runs_user", columnList = "user_id"),
        @Index(name = "ix_runs_notebook", columnList = "notebook_id"),
        @Index(name = "ix_runs_status", columnList = "status")})
@Getter
@Setter
public class AgentRun extends BaseEntity {
    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;
    @Column(name = "notebook_id", nullable = false, length = 36)
    private String notebookId;
    @Column(nullable = false, columnDefinition = "longtext")
    private String query;
    @Column(name = "attachment_ids", nullable = false, columnDefinition = "json")
    private String attachmentIds = "[]";
    @Column(nullable = false, length = 32)
    private String status = "pending";
    @Column(nullable = false, columnDefinition = "json")
    private String state = "{}";
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;
}
