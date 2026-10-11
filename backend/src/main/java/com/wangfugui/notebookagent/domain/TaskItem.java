package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "task_items", indexes = @Index(name = "ix_task_items_run", columnList = "run_id"))
@Getter
@Setter
public class TaskItem extends BaseEntity {
    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;
    @Column(nullable = false, length = 512)
    private String title;
    @Column(nullable = false, length = 32)
    private String status = "pending";
    @Column(nullable = false)
    private int ordinal;
}
