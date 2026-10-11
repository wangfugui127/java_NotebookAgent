package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "document_blocks", indexes = @Index(name = "ix_blocks_type", columnList = "block_type"))
@Getter
@Setter
public class DocumentBlock extends BaseEntity {
    @Column(name = "document_version_id", nullable = false, length = 36)
    private String documentVersionId;
    @Column(name = "section_id", length = 36)
    private String sectionId;
    @Column(nullable = false)
    private int ordinal;
    @Column(name = "block_type", nullable = false, length = 24)
    private String blockType;
    @Column(nullable = false, columnDefinition = "longtext")
    private String text;
    @Column(nullable = false, columnDefinition = "longtext")
    private String markdown;
    @Column(name = "page_number")
    private Integer pageNumber;
    @Column(nullable = false, columnDefinition = "json")
    private String bbox = "[]";
    @Column(name = "char_start", nullable = false)
    private int charStart;
    @Column(name = "char_end", nullable = false)
    private int charEnd;
}
