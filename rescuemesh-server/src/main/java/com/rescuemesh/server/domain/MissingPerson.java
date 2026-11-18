package com.rescuemesh.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Server-side record of one missing-person case (MISSING_PERSON envelope).
 *
 * <p>Since V3 (photo transfer design §3) a case can be linked to the
 * PERSON_FOUND_UPDATE that reported the person found: the update's payload
 * {@code caseId} is this record's envelope {@code messageId}, and the link
 * lands in {@code foundMessageId} / {@code foundAt} with status
 * {@link Status#FOUND_REPORTED}. The link is informational only — a
 * coordinator reviews and closes cases on the dashboard; nothing
 * auto-closes.
 *
 * <p>Since V4 a coordinator/admin review decision is persisted on the
 * case ({@code reviewStatus} CONFIRMED / REJECTED, {@code reviewedBy},
 * {@code reviewedAt}). The review never changes the sync-derived
 * {@link Status} — it is the human judgement layered on top of it.
 */
@Entity
@Table(name = "missing_person")
public class MissingPerson {

    public enum Status {
        OPEN,
        FOUND_REPORTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Envelope messageId of the MISSING_PERSON record — the case key (V3). */
    @Column(unique = true)
    private String messageId;

    @Column(nullable = false)
    private String name;

    private Integer age;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String lastSeenArea;

    private Instant lastSeenAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.OPEN;

    /** Envelope messageId of the linked PERSON_FOUND_UPDATE, if any (V3). */
    private String foundMessageId;

    /** Envelope createdAt of the linked PERSON_FOUND_UPDATE, if any (V3). */
    private Instant foundAt;

    /** originDeviceId of the device that reported the case. */
    private String reportedByDeviceId;

    /** Dashboard review decision (V4): null, "CONFIRMED" or "REJECTED". */
    private String reviewStatus;

    /** Username of the coordinator/admin who reviewed the case (V4). */
    private String reviewedBy;

    /** When the case was reviewed (V4). */
    private Instant reviewedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLastSeenArea() {
        return lastSeenArea;
    }

    public void setLastSeenArea(String lastSeenArea) {
        this.lastSeenArea = lastSeenArea;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getFoundMessageId() {
        return foundMessageId;
    }

    public void setFoundMessageId(String foundMessageId) {
        this.foundMessageId = foundMessageId;
    }

    public Instant getFoundAt() {
        return foundAt;
    }

    public void setFoundAt(Instant foundAt) {
        this.foundAt = foundAt;
    }

    public String getReportedByDeviceId() {
        return reportedByDeviceId;
    }

    public void setReportedByDeviceId(String reportedByDeviceId) {
        this.reportedByDeviceId = reportedByDeviceId;
    }

    public String getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(String reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(String reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
