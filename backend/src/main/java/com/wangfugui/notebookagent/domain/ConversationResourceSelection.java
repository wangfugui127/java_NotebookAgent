package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "conversation_resource_selections",
        indexes = @Index(name = "ix_selection_conversation", columnList = "conversation_id"))
@Getter
@Setter
public class ConversationResourceSelection extends BaseEntity {
    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;
    @Column(name = "document_id", nullable = false, length = 36)
    private String documentId;
    @Column(nullable = false, length = 16)
    private String mode = "summary";
}
