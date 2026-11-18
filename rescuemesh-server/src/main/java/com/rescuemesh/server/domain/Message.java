package com.rescuemesh.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A mesh envelope as received by the sync server. Field names mirror the
 * fixed envelope contract exactly: messageId, eventType, originDeviceId,
 * createdAt, expiresAt, priority, hopLimit, hopCount, hopTrace, payload,
 * signature.
 *
 * <p>Note for V1: {@code hopTrace} is stored as a plain TEXT column holding
 * the hop list serialised as JSON (e.g. {@code ["devA","devB"]}). A separate
 * HopEntry table can be normalised out of it in a later prompt if queries
 * ever need per-hop rows; the envelope itself does not change.
 */
@Entity
@Table(name = "message")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Envelope messageId (UUID from the origin device). Unique — re-syncs dedupe on it. */
    @Column(nullable = false, unique = true)
    private String messageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventType eventType;

    @Column(nullable = false)
    private String originDeviceId;

    /** Envelope createdAt (origin device clock). */
    @Column(nullable = false)
    private Instant createdAt;

    /** Envelope expiresAt; null if the event type has no expiry. */
    private Instant expiresAt;

    /** 1 = Critical, 2 = High, 3 = Normal (fixed plan mapping). */
    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private int hopLimit;

    @Column(nullable = false)
    private int hopCount;

    /** Serialised hop list (JSON array of device ids), stored as text in V1. */
    @Column(columnDefinition = "TEXT")
    private String hopTrace;

    /** Event payload, JSON text. */
    @Column(columnDefinition = "TEXT")
    private String payload;

    /** Ed25519 signature (Base64url) over the canonical envelope; verified from Prompt 4 on. */
    @Column(columnDefinition = "TEXT")
    private String signature;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public EventType getEventType() {
        return eventType;
    }

    public void setEventType(EventType eventType) {
        this.eventType = eventType;
    }

    public String getOriginDeviceId() {
        return originDeviceId;
    }

    public void setOriginDeviceId(String originDeviceId) {
        this.originDeviceId = originDeviceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public int getHopLimit() {
        return hopLimit;
    }

    public void setHopLimit(int hopLimit) {
        this.hopLimit = hopLimit;
    }

    public int getHopCount() {
        return hopCount;
    }

    public void setHopCount(int hopCount) {
        this.hopCount = hopCount;
    }

    public String getHopTrace() {
        return hopTrace;
    }

    public void setHopTrace(String hopTrace) {
        this.hopTrace = hopTrace;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }
}
