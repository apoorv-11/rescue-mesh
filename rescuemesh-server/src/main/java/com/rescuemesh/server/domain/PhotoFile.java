package com.rescuemesh.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A verified record photo, stored in the database (schema V5).
 *
 * <p>Photos were originally files under {@code PHOTO_DIR}; on the
 * production host the filesystem is ephemeral and every redeploy wiped
 * them while the records survived. The bytes land here only AFTER
 * {@code PhotoController} has verified them against the signed
 * {@code photoRef} (size + SHA-256), so a row's presence is proof the
 * photo is the one the origin signed for.
 */
@Entity
@Table(name = "photo_file")
public class PhotoFile {

    @Id
    @Column(name = "message_id", length = 255)
    private String messageId;

    /* length matters: Hibernate's default binary length is 255, which
     * silently truncates/rejects real photos (cap is 512,000 bytes). */
    @Column(name = "data", nullable = false, length = 524288)
    private byte[] data;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PhotoFile() {
    }

    public PhotoFile(String messageId, byte[] data, Instant createdAt) {
        this.messageId = messageId;
        this.data = data;
        this.createdAt = createdAt;
    }

    public String getMessageId() {
        return messageId;
    }

    public byte[] getData() {
        return data;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
