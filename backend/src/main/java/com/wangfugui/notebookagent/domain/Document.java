package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "documents", indexes = @Index(name = "ix_documents_notebook", columnList = "notebook_id"))
@Getter
@Setter
public class Document extends BaseEntity {
    @Column(name = "notebook_id", nullable = false, length = 36)
    private String notebookId;
    @Column(nullable = false, length = 512)
    private String title;
    @Column(name = "media_type", nullable = false, length = 120)
    private String mediaType;
    @Column(name = "active_version_id", length = 36)
    private String activeVersionId;
}
