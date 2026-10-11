package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "refresh_tokens", indexes = @Index(name = "ix_refresh_user", columnList = "user_id"))
@Getter
@Setter
public class RefreshToken extends BaseEntity {
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;
    @Column(nullable = false, unique = true, length = 64)
    private String fingerprint;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;
}
