package com.rescuemesh.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A one-time code that grants {@code role} to one user when redeemed at
 * {@code POST /profile/role-code} (auth v2). Created by an admin — either
 * directly or by approving a {@link RoleApplication} (then
 * {@code application} points at it; null for direct codes).
 *
 * <p>Only the SHA-256 hex hash of the plaintext code is stored. The
 * plaintext is emailed to the user and, deliberately, ALSO returned once in
 * the admin's approve/create response: in the offline demo the mail service
 * may be log-only, so the admin needs a way to hand the code over in person.
 * See the README "Accounts, email verification &amp; role access (v2)".
 */
@Entity
@Table(name = "role_access_code")
public class RoleAccessCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "code_hash", nullable = false, unique = true)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Set when the code is redeemed; a code with a used_at can never be used again. */
    @Column(name = "used_at")
    private Instant usedAt;

    /** Username of the admin who created this code. */
    @Column(name = "created_by")
    private String createdBy;

    @ManyToOne
    @JoinColumn(name = "application_id")
    private RoleApplication application;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public void setCodeHash(String codeHash) {
        this.codeHash = codeHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(Instant usedAt) {
        this.usedAt = usedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public RoleApplication getApplication() {
        return application;
    }

    public void setApplication(RoleApplication application) {
        this.application = application;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
