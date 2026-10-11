package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "messages", indexes = @Index(name = "ix_messages_conversation", columnList = "conversation_id"))
@Getter
@Setter
public class Message extends BaseEntity {
    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;
    @Column(nullable = false, length = 24)
    private String role;
    @Column(nullable = false, columnDefinition = "longtext")
    private String content;
    @Column(nullable = false, columnDefinition = "json")
    private String attachments = "[]";
    @Column(name = "provider_state", nullable = false, columnDefinition = "json")
    private String providerState = "{}";
}
