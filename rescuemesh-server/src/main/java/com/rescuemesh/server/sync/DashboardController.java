package com.rescuemesh.server.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rescuemesh.server.domain.Announcement;
import com.rescuemesh.server.domain.MissingPerson;
import com.rescuemesh.server.domain.SOSRecord;
import com.rescuemesh.server.domain.SyncAudit;
import com.rescuemesh.server.repository.AnnouncementRepository;
import com.rescuemesh.server.repository.MissingPersonRepository;
import com.rescuemesh.server.repository.SOSRecordRepository;
import com.rescuemesh.server.repository.SyncAuditRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard write/read endpoints that sit beside the sync-derived record
 * views (see {@link RecordsController} for the read side):
 *
 * <ul>
 *   <li>{@code POST /records/announcements} — a coordinator/admin publishes
 *       an announcement directly (COORDINATOR/ADMIN only, enforced in
 *       SecurityConfig). The announcement is stored as a normal
 *       {@link Announcement} row, so it appears in
 *       {@code GET /records/announcements} like a synced one.</li>
 *   <li>{@code POST /records/missing/{messageId}/review} — persists a
 *       coordinator/admin case review (V4: CONFIRMED / REJECTED). The
 *       review never changes the sync-derived case status.</li>
 *   <li>{@code POST /records/sos/{messageId}/resolve} — marks a synced SOS
 *       RESOLVED (COORDINATOR/ADMIN only). The status enum already carried
 *       RESOLVED; the dashboard drops resolved records from its active
 *       queue. Idempotent: resolving twice returns the record again.</li>
 *   <li>{@code GET /records/audit} — the latest {@link SyncAudit} rows for
 *       the dashboard's sync strip.</li>
 * </ul>
 *
 * Item JSON shapes are rendered by {@link RecordsController} so a create /
 * review response is byte-identical in shape to the corresponding list
 * item. All timestamps are epoch-millisecond integers.
 */
@RestController
public class DashboardController {

    private final AnnouncementRepository announcementRepository;
    private final MissingPersonRepository missingPersonRepository;
    private final SOSRecordRepository sosRecordRepository;
    private final SyncAuditRepository syncAuditRepository;
    private final RecordsController recordsController;
    private final ObjectMapper mapper = new ObjectMapper();

    public DashboardController(AnnouncementRepository announcementRepository,
                               MissingPersonRepository missingPersonRepository,
                               SOSRecordRepository sosRecordRepository,
                               SyncAuditRepository syncAuditRepository,
                               RecordsController recordsController) {
        this.announcementRepository = announcementRepository;
        this.missingPersonRepository = missingPersonRepository;
        this.sosRecordRepository = sosRecordRepository;
        this.syncAuditRepository = syncAuditRepository;
        this.recordsController = recordsController;
    }

    @PostMapping(value = "/records/announcements", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> createAnnouncement(@AuthenticationPrincipal Jwt jwt,
                                                     @RequestBody String rawBody) throws Exception {
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "request body is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            return error(HttpStatus.BAD_REQUEST, "request must be a JSON object");
        }
        String title = textOrNull(root.get("title"));
        if (title == null || title.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "title is required");
        }
        String body = textOrNull(root.get("body"));
        if (body == null || body.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "body is required");
        }
        int priority = 3;
        JsonNode priorityNode = root.get("priority");
        if (priorityNode != null && !priorityNode.isNull()) {
            if (!priorityNode.isIntegralNumber()
                    || priorityNode.asInt() < 1 || priorityNode.asInt() > 3) {
                return error(HttpStatus.BAD_REQUEST, "priority must be 1, 2 or 3");
            }
            priority = priorityNode.asInt();
        }
        Instant expiresAt = null;
        JsonNode validUntilNode = root.get("validUntil");
        if (validUntilNode != null && !validUntilNode.isNull()) {
            if (!validUntilNode.isNumber()) {
                return error(HttpStatus.BAD_REQUEST, "validUntil must be epoch milliseconds");
            }
            expiresAt = Instant.ofEpochMilli(validUntilNode.asLong());
        }

        Announcement announcement = new Announcement();
        announcement.setTitle(title);
        announcement.setBody(body);
        announcement.setPriority(priority);
        announcement.setExpiresAt(expiresAt);
        announcement.setCreatedBy(jwt.getSubject());
        Announcement saved = announcementRepository.save(announcement);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mapper.writeValueAsString(recordsController.announcementItem(saved)));
    }

    @PostMapping(value = "/records/missing/{messageId}/review",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> reviewCase(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable String messageId,
                                             @RequestBody String rawBody) throws Exception {
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "request body is not valid JSON");
        }
        String decision = root == null ? null : textOrNull(root.get("decision"));
        if (!"CONFIRMED".equals(decision) && !"REJECTED".equals(decision)) {
            return error(HttpStatus.BAD_REQUEST, "decision must be CONFIRMED or REJECTED");
        }
        Optional<MissingPerson> found = missingPersonRepository.findByMessageId(messageId);
        if (found.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "unknown messageId");
        }
        // The review is persisted on the case; the sync-derived status
        // (OPEN / FOUND_REPORTED) is deliberately left untouched.
        MissingPerson person = found.get();
        person.setReviewStatus(decision);
        person.setReviewedBy(jwt.getSubject());
        person.setReviewedAt(Instant.now());
        MissingPerson saved = missingPersonRepository.save(person);
        return ResponseEntity.ok(mapper.writeValueAsString(recordsController.caseItem(saved)));
    }

    @PostMapping(value = "/records/sos/{messageId}/resolve",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> resolveSos(@PathVariable String messageId) throws Exception {
        Optional<SOSRecord> found = sosRecordRepository.findByMessageId(messageId);
        if (found.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "unknown messageId");
        }
        SOSRecord record = found.get();
        record.setStatus(SOSRecord.Status.RESOLVED);
        SOSRecord saved = sosRecordRepository.save(record);
        return ResponseEntity.ok(mapper.writeValueAsString(sosItem(saved)));
    }

    /** One SOS item, byte-identical in shape to the GET /records/sos items. */
    private ObjectNode sosItem(SOSRecord r) {
        ObjectNode o = mapper.createObjectNode();
        o.put("id", r.getId());
        o.put("messageId", r.getMessageId());
        o.put("originDeviceId", r.getOriginDeviceId());
        o.put("status", r.getStatus().name());
        if (r.getLatitude() != null) {
            o.put("latitude", r.getLatitude());
        } else {
            o.putNull("latitude");
        }
        if (r.getLongitude() != null) {
            o.put("longitude", r.getLongitude());
        } else {
            o.putNull("longitude");
        }
        o.put("note", r.getNote());
        if (r.getCreatedAt() != null) {
            o.put("createdAt", r.getCreatedAt().toEpochMilli());
        } else {
            o.putNull("createdAt");
        }
        if (r.getUpdatedAt() != null) {
            o.put("updatedAt", r.getUpdatedAt().toEpochMilli());
        } else {
            o.putNull("updatedAt");
        }
        return o;
    }

    @GetMapping(value = "/records/audit", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> audit() throws Exception {
        ArrayNode out = mapper.createArrayNode();
        for (SyncAudit row : syncAuditRepository.findTop200ByOrderByCreatedAtDesc()) {
            ObjectNode o = mapper.createObjectNode();
            o.put("id", row.getId());
            o.put("deviceId", row.getDeviceId());
            o.put("messageId", row.getMessageId());
            o.put("action", row.getAction());
            if (row.getHopCount() != null) {
                o.put("hopCount", row.getHopCount());
            } else {
                o.putNull("hopCount");
            }
            o.put("detail", row.getDetail());
            if (row.getCreatedAt() != null) {
                o.put("createdAt", row.getCreatedAt().toEpochMilli());
            } else {
                o.putNull("createdAt");
            }
            out.add(o);
        }
        return ResponseEntity.ok(mapper.writeValueAsString(out));
    }

    private static String textOrNull(JsonNode node) {
        return node == null || !node.isTextual() ? null : node.asText();
    }

    private ResponseEntity<String> error(HttpStatus status, String message) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("error", message);
        return ResponseEntity.status(status).body(mapper.writeValueAsString(body));
    }
}
