package com.rescuemesh.server.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rescuemesh.server.domain.Message;
import com.rescuemesh.server.repository.MessageRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Photo gateway endpoints (photo transfer design §3). A full photo (up to
 * 500 KB) never rides inside {@code /sync/batch} JSON — it uploads here as
 * a raw JPEG body, separately, and is accepted only when its size and
 * SHA-256 match the {@code photoRef} inside the already-synced, origin-
 * signed envelope payload. Both endpoints run under the default
 * authenticated rule (no new public endpoints).
 */
@RestController
public class PhotoController {

    private final MessageRepository messageRepository;
    private final PhotoStorage photoStorage;
    private final RecordsChangedPublisher recordsChangedPublisher;
    private final ObjectMapper mapper = new ObjectMapper();

    public PhotoController(MessageRepository messageRepository, PhotoStorage photoStorage,
                           RecordsChangedPublisher recordsChangedPublisher) {
        this.messageRepository = messageRepository;
        this.photoStorage = photoStorage;
        this.recordsChangedPublisher = recordsChangedPublisher;
    }

    /**
     * {@code POST /sync/photo?messageId=<id>} with the raw JPEG as the body.
     * 404 unknown messageId; 400 when the envelope carries no photoRef or
     * the bytes do not match it. Idempotent: the identical photo again →
     * 200 {@code ALREADY_EXISTS}.
     */
    @PostMapping(value = "/sync/photo", consumes = MediaType.IMAGE_JPEG_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> uploadPhoto(@RequestParam("messageId") String messageId,
                                              @RequestBody byte[] body) throws Exception {
        if (!PhotoStorage.isSafeId(messageId)) {
            return error(HttpStatus.NOT_FOUND, "unknown messageId");
        }
        Optional<Message> message = messageRepository.findByMessageId(messageId);
        if (message.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "unknown messageId");
        }
        JsonNode photoRef = photoRefOf(message.get());
        if (photoRef == null) {
            return error(HttpStatus.BAD_REQUEST, "message has no photoRef");
        }
        long expectedSize = photoRef.get("sizeBytes").longValue();
        if (body.length != expectedSize) {
            return error(HttpStatus.BAD_REQUEST,
                    "size mismatch: photoRef says " + expectedSize + " bytes, got " + body.length);
        }
        String expectedSha = photoRef.get("sha256").textValue();
        if (!sha256Hex(body).equalsIgnoreCase(expectedSha)) {
            return error(HttpStatus.BAD_REQUEST, "sha256 mismatch");
        }
        PhotoStorage.StoreResult result = photoStorage.store(messageId, body);
        recordsChangedPublisher.publishPhotoChanged(messageId, System.currentTimeMillis());
        ObjectNode response = mapper.createObjectNode();
        response.put("messageId", messageId);
        response.put("status", result.name());
        response.put("sizeBytes", body.length);
        return ResponseEntity.ok(mapper.writeValueAsString(response));
    }

    /** {@code GET /records/photos/{messageId}} — the stored JPEG, or 404. */
    @GetMapping(value = "/records/photos/{messageId}", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> photo(@PathVariable("messageId") String messageId) {
        Optional<byte[]> bytes = photoStorage.read(messageId);
        if (bytes.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(bytes.get());
    }

    /**
     * The validated {@code photoRef} object out of a stored envelope's
     * payload; null when absent or malformed (not an object, or missing a
     * textual sha256 / integral sizeBytes).
     */
    static JsonNode photoRefOf(Message message, ObjectMapper mapper) {
        try {
            if (message.getPayload() == null) {
                return null;
            }
            JsonNode ref = mapper.readTree(message.getPayload()).get("photoRef");
            if (ref == null || !ref.isObject()
                    || ref.get("sha256") == null || !ref.get("sha256").isTextual()
                    || ref.get("sizeBytes") == null || !ref.get("sizeBytes").isIntegralNumber()) {
                return null;
            }
            return ref;
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode photoRefOf(Message message) {
        return photoRefOf(message, mapper);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private ResponseEntity<String> error(HttpStatus status, String message) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("error", message);
        return ResponseEntity.status(status).body(mapper.writeValueAsString(body));
    }
}
