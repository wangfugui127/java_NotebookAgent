package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "notebooks", indexes = @Index(name = "ix_notebooks_owner", columnList = "owner_id"))
@Getter
@Setter
public class Notebook extends BaseEntity {
    @Column(name = "owner_id", nullable = false, length = 36)
    private String ownerId;
    @Column(nullable = false, length = 255)
    private String title;
    @Column(nullable = false, columnDefinition = "text")
    private String description = "";
}
