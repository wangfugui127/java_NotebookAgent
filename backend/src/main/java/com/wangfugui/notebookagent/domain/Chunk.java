package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "chunks", indexes = {
        @Index(name = "ix_chunks_version", columnList = "document_version_id"),
        @Index(name = "ix_chunks_type", columnList = "chunk_type"),
        @Index(name = "ix_chunks_active", columnList = "is_active")})
@Getter
@Setter
public class Chunk extends BaseEntity {
    @Column(name = "document_version_id", nullable = false, length = 36)
    private String documentVersionId;
    @Column(name = "section_id", length = 36)
    private String sectionId;
    @Column(nullable = false)
    private int ordinal;
    @Column(name = "page_start")
    private Integer pageStart;
    @Column(name = "page_end")
    private Integer pageEnd;
    @Column(name = "char_start", nullable = false)
    private int charStart;
    @Column(name = "char_end", nullable = false)
    private int charEnd;
    @Column(nullable = false, columnDefinition = "longtext")
    private String content;
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;
    @Column(name = "chunk_type", nullable = false, length = 24)
    private String chunkType = "paragraph";
    @Column(name = "block_ids", nullable = false, columnDefinition = "json")
    private String blockIds = "[]";
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;
}
