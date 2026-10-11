package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "citations", indexes = @Index(name = "ix_citations_message", columnList = "message_id"))
@Getter
@Setter
public class Citation extends BaseEntity {
    @Column(name = "message_id", nullable = false, length = 36)
    private String messageId;
    @Column(name = "evidence_id", nullable = false, length = 36)
    private String evidenceId;
    @Column(name = "claim_text", nullable = false, columnDefinition = "text")
    private String claimText;
}
