package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "conversation_summaries",
        indexes = @Index(name = "ix_summaries_conversation", columnList = "conversation_id"))
@Getter
@Setter
public class ConversationSummary extends BaseEntity {
    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;
    @Column(name = "through_message_id", nullable = false, length = 36)
    private String throughMessageId;
    @Column(nullable = false, columnDefinition = "json")
    private String summary = "{}";
}
