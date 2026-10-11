package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@jakarta.persistence.AttributeOverride(name = "id", column = @Column(name = "run_id", length = 36, nullable = false))
@Entity
@Table(name = "run_transcripts", indexes = @Index(name = "ix_transcripts_conversation", columnList = "conversation_id"))
@Getter
@Setter
public class RunTranscript extends BaseEntity {
    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;
    @Column(nullable = false, columnDefinition = "json")
    private String messages = "[]";
}
