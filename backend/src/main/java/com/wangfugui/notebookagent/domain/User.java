package com.wangfugui.notebookagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User extends BaseEntity {
    @Column(nullable = false, unique = true, length = 320)
    private String email;
    @Column(name = "password_hash", nullable = false, length = 512)
    private String passwordHash;
    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName = "";
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;
    @Column(name = "approval_mode", nullable = false, length = 16)
    private String approvalMode = "confirm";
}
