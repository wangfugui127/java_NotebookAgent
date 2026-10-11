package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "checkpoints", indexes = @Index(name = "ix_checkpoints_run", columnList = "run_id"))
@Getter
@Setter
public class Checkpoint extends BaseEntity {
    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;
    @Column(nullable = false, length = 80)
    private String boundary;
    @Column(nullable = false, columnDefinition = "json")
    private String state = "{}";
}
