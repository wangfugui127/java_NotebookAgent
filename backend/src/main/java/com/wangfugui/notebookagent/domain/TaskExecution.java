package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "task_execution", indexes = {
        @Index(name = "ix_task_status", columnList = "status"),
        @Index(name = "ix_task_next_attempt", columnList = "next_attempt_at")})
@Getter
@Setter
public class TaskExecution extends BaseEntity {

    public static final String NEW = "NEW";
    public static final String DISPATCHED = "DISPATCHED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String RETRYING = "RETRYING";
    public static final String DEAD = "DEAD";

    @Column(name = "task_type", nullable = false, length = 64)
    private String taskType;
    @Column(nullable = false, columnDefinition = "json")
    private String payload = "{}";
    @Column(name = "dedup_key", unique = true, length = 191)
    private String dedupKey;
    @Column(nullable = false, length = 24)
    private String status = NEW;
    @Column(nullable = false)
    private int attempt;
    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 1;
    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;
    @Column(name = "lease_until")
    private Instant leaseUntil;
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;
    @Column(name = "stream_id", length = 64)
    private String streamId;
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;
}
