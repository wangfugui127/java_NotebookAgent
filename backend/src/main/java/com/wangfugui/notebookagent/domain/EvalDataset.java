package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "eval_datasets", indexes = @Index(name = "ix_eval_datasets_user", columnList = "user_id"))
@Getter
@Setter
public class EvalDataset extends BaseEntity {
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;
    @Column(nullable = false, length = 255)
    private String name;
    @Column(nullable = false, columnDefinition = "text")
    private String description = "";
}
