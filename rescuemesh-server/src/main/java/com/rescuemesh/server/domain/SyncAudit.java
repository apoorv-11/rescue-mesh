package com.rescuemesh.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Audit row for one sync-related event (device token issued, batch pushed,
 * duplicates dropped, ...). Written by the gateway-sync code in later
 * prompts; the table and entity exist from V1 so the schema is stable.
 */
@Entity
@Table(name = "sync_audit")
public class SyncAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Device the event concerns (envelope originDeviceId / bound deviceId). */
    private String deviceId;

    /** Envelope messageId, when the event is about one message. */
    private String messageId;

    /** Short machine-readable action name, e.g. DEVICE_TOKEN_ISSUED, SYNC_PUSH. */
    @Column(nullable = false)
    private String action;

    /** hopCount of the message at sync time, when applicable. */
    private Integer hopCount;

    @Column(columnDefinition = "TEXT")
    private String detail;

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

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Integer getHopCount() {
        return hopCount;
    }

    public void setHopCount(Integer hopCount) {
        this.hopCount = hopCount;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
