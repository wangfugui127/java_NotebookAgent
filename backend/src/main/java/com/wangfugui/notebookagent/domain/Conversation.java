package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "conversations", indexes = {
        @Index(name = "ix_conversations_user", columnList = "user_id"),
        @Index(name = "ix_conversations_notebook", columnList = "notebook_id")})
@Getter
@Setter
public class Conversation extends BaseEntity {
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;
    @Column(name = "notebook_id", nullable = false, length = 36)
    private String notebookId;
    @Column(nullable = false, length = 255)
    private String title = "New conversation";
}
