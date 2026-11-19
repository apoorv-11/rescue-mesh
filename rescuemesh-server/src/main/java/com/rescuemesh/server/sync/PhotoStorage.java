package com.rescuemesh.server.sync;

import com.rescuemesh.server.domain.PhotoFile;
import com.rescuemesh.server.repository.PhotoFileRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Verified record photos, stored in the database (schema V5).
 *
 * <p>Bytes only ever land here AFTER {@link PhotoController} has
 * verified them against the signed {@code photoRef} in the stored
 * envelope (size + SHA-256), so a row's presence is proof the photo is
 * the one the origin signed for.
 *
 * <p>History: photos were originally files under {@code PHOTO_DIR}.
 * On the production host (Render free tier) the filesystem is
 * ephemeral — every redeploy wiped the uploaded files while the
 * database rows (records, cases, reviews) survived, and the dashboard
 * showed "photo not stored" for photos that had uploaded fine. The
 * public API of this class is unchanged from the file version, so no
 * caller needed to change.
 */
@Component
public class PhotoStorage {

    /** messageIds are record ids — keep them to one safe segment. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    public enum StoreResult {
        STORED,
        ALREADY_EXISTS
    }

    private final PhotoFileRepository repository;

    public PhotoStorage(PhotoFileRepository repository) {
        this.repository = repository;
    }

    public static boolean isSafeId(String messageId) {
        return messageId != null && SAFE_ID.matcher(messageId).matches()
                && !messageId.contains("..");
    }

    /** True when a verified photo exists for this record. */
    public boolean hasPhoto(String messageId) {
        return isSafeId(messageId) && repository.existsById(messageId);
    }

    /** The stored photo bytes, when present. */
    public Optional<byte[]> read(String messageId) {
        if (!isSafeId(messageId)) {
            return Optional.empty();
        }
        return repository.findById(messageId).map(PhotoFile::getData);
    }

    /**
     * Stores verified bytes. Re-storing is a no-op success
     * ({@link StoreResult#ALREADY_EXISTS}); callers have already checked
     * the SHA-256 against the signed photoRef, so an existing row can
     * only ever hold these same bytes. A concurrent first store of the
     * same photo loses on the primary key and is reported the same way.
     */
    public StoreResult store(String messageId, byte[] bytes) {
        if (!isSafeId(messageId)) {
            throw new IllegalArgumentException("unsafe messageId: " + messageId);
        }
        if (repository.existsById(messageId)) {
            return StoreResult.ALREADY_EXISTS;
        }
        try {
            repository.save(new PhotoFile(messageId, bytes, Instant.now()));
            return StoreResult.STORED;
        } catch (DataIntegrityViolationException failure) {
            /* Only a lost insert race may be reported as ALREADY_EXISTS —
             * anything else is a real storage failure and must surface,
             * never be masked as success. */
            if (repository.existsById(messageId)) {
                return StoreResult.ALREADY_EXISTS;
            }
            throw failure;
        }
    }
}
