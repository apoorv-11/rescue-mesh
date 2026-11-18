package com.rescuemesh.server.sync;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Verified photo files on disk (photo transfer design §3): one JPEG per
 * record, {@code <photoDir>/<messageId>.jpg}. Bytes only ever land here
 * AFTER {@link PhotoController} has verified them against the signed
 * {@code photoRef} in the stored envelope (size + SHA-256), so a file's
 * presence is proof the photo is the one the origin signed for.
 *
 * <p>Writes are atomic (temp file in the same directory, then move), so a
 * concurrent reader never sees a half-written photo.
 */
@Component
public class PhotoStorage {

    /** messageIds become file names — keep them to one safe path segment. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    public enum StoreResult {
        STORED,
        ALREADY_EXISTS
    }

    private final Path photoDir;

    public PhotoStorage(@Value("${rescuemesh.photos.dir}") String photoDir) {
        this.photoDir = Path.of(photoDir);
    }

    public static boolean isSafeId(String messageId) {
        return messageId != null && SAFE_ID.matcher(messageId).matches()
                && !messageId.contains("..");
    }

    /** True when a verified photo file exists for this record. */
    public boolean hasPhoto(String messageId) {
        return isSafeId(messageId) && Files.isRegularFile(fileFor(messageId));
    }

    /** The stored photo bytes, when present. */
    public Optional<byte[]> read(String messageId) {
        if (!hasPhoto(messageId)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(fileFor(messageId)));
        } catch (IOException e) {
            throw new UncheckedIOException("reading stored photo for " + messageId, e);
        }
    }

    /**
     * Stores verified bytes. Re-storing identical bytes is a no-op success
     * ({@link StoreResult#ALREADY_EXISTS}); callers have already checked the
     * SHA-256 against the signed photoRef, so an existing file can only ever
     * hold these same bytes.
     */
    public StoreResult store(String messageId, byte[] bytes) {
        if (!isSafeId(messageId)) {
            throw new IllegalArgumentException("unsafe messageId: " + messageId);
        }
        try {
            Files.createDirectories(photoDir);
            Path target = fileFor(messageId);
            if (Files.isRegularFile(target)) {
                return StoreResult.ALREADY_EXISTS;
            }
            Path temp = Files.createTempFile(photoDir, messageId + "-", ".tmp");
            try {
                Files.write(temp, bytes);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.FileAlreadyExistsException concurrentStore) {
                    // A concurrent upload of the same verified photo won the
                    // race — the file already holds these exact bytes.
                    return StoreResult.ALREADY_EXISTS;
                } catch (IOException atomicMoveUnsupported) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            return StoreResult.STORED;
        } catch (IOException e) {
            throw new UncheckedIOException("storing photo for " + messageId, e);
        }
    }

    private Path fileFor(String messageId) {
        return photoDir.resolve(messageId + ".jpg");
    }
}
