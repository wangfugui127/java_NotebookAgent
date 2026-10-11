package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "sections", indexes = @Index(name = "ix_sections_version", columnList = "document_version_id"))
@Getter
@Setter
public class Section extends BaseEntity {
    @Column(name = "document_version_id", nullable = false, length = 36)
    private String documentVersionId;
    @Column(nullable = false, length = 512)
    private String title = "";
    @Column(nullable = false)
    private int ordinal;
    @Column(name = "page_start")
    private Integer pageStart;
    @Column(name = "page_end")
    private Integer pageEnd;
}
