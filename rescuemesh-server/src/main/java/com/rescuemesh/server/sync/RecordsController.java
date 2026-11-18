package com.rescuemesh.server.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rescuemesh.server.domain.Announcement;
import com.rescuemesh.server.domain.Message;
import com.rescuemesh.server.domain.MissingPerson;
import com.rescuemesh.server.domain.SOSRecord;
import com.rescuemesh.server.repository.AnnouncementRepository;
import com.rescuemesh.server.repository.MessageRepository;
import com.rescuemesh.server.repository.MissingPersonRepository;
import com.rescuemesh.server.repository.SOSRecordRepository;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read endpoints for synced records (contracts §5): authenticated, newest
 * first, capped at 200. Simple list DTOs for the dashboard / Postman; all
 * timestamps are epoch-millisecond integers (the project rule: no ISO strings
 * on any wire). {@code /records/messages} returns envelope-shaped JSON — the
 * 11 contract fields, with hopTrace/payload as JSON values.
 */
@RestController
public class RecordsController {

    private final SOSRecordRepository sosRecordRepository;
    private final MissingPersonRepository missingPersonRepository;
    private final AnnouncementRepository announcementRepository;
    private final MessageRepository messageRepository;
    private final PhotoStorage photoStorage;
    private final ObjectMapper mapper = new ObjectMapper();

    public RecordsController(SOSRecordRepository sosRecordRepository,
                             MissingPersonRepository missingPersonRepository,
                             AnnouncementRepository announcementRepository,
                             MessageRepository messageRepository,
                             PhotoStorage photoStorage) {
        this.sosRecordRepository = sosRecordRepository;
        this.missingPersonRepository = missingPersonRepository;
        this.announcementRepository = announcementRepository;
        this.messageRepository = messageRepository;
        this.photoStorage = photoStorage;
    }

    @GetMapping(value = "/records/sos", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> sos() throws Exception {
        ArrayNode out = mapper.createArrayNode();
        for (SOSRecord r : sosRecordRepository.findTop200ByOrderByCreatedAtDesc()) {
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
            o.put("createdAt", epochMs(r.getCreatedAt()));
            o.put("updatedAt", epochMs(r.getUpdatedAt()));
            out.add(o);
        }
        return ResponseEntity.ok(mapper.writeValueAsString(out));
    }

    /**
     * The case view (V3, photo transfer design §3): each item is one case —
     * the Missing record, the signed {@code photoRef} from its envelope
     * (null when the record carries no full photo), {@code hasPhoto} (a
     * verified photo file is stored on this server, fetchable via
     * {@code GET /records/photos/{messageId}}), and the found link
     * ({@code foundMessageId} / {@code foundAt}) when a PERSON_FOUND_UPDATE
     * has been linked. The dashboard renders Missing vs Found photos
     * side-by-side from this view plus the photo endpoint.
     */
    @GetMapping(value = "/records/missing", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> missing() throws Exception {
        ArrayNode out = mapper.createArrayNode();
        for (MissingPerson p : missingPersonRepository.findTop200ByOrderByCreatedAtDesc()) {
            out.add(caseItem(p));
        }
        return ResponseEntity.ok(mapper.writeValueAsString(out));
    }

    /**
     * One case item in exactly the {@code GET /records/missing} shape —
     * shared with {@link DashboardController}, whose review response must
     * render the updated case identically.
     */
    ObjectNode caseItem(MissingPerson p) {
        ObjectNode o = mapper.createObjectNode();
        o.put("id", p.getId());
        if (p.getMessageId() != null) {
            o.put("messageId", p.getMessageId());
        } else {
            o.putNull("messageId");
        }
        o.put("name", p.getName());
        if (p.getAge() != null) {
            o.put("age", p.getAge());
        } else {
            o.putNull("age");
        }
        o.put("description", p.getDescription());
        o.put("lastSeenArea", p.getLastSeenArea());
        if (p.getLastSeenAt() != null) {
            o.put("lastSeenAt", p.getLastSeenAt().toEpochMilli());
        } else {
            o.putNull("lastSeenAt");
        }
        o.put("status", p.getStatus().name());
        o.put("reportedByDeviceId", p.getReportedByDeviceId());
        JsonNode photoRef = p.getMessageId() == null ? null
                : messageRepository.findByMessageId(p.getMessageId())
                        .map(m -> PhotoController.photoRefOf(m, mapper))
                        .orElse(null);
        if (photoRef != null) {
            o.set("photoRef", photoRef);
        } else {
            o.putNull("photoRef");
        }
        o.put("hasPhoto", p.getMessageId() != null && photoStorage.hasPhoto(p.getMessageId()));
        if (p.getFoundMessageId() != null) {
            o.put("foundMessageId", p.getFoundMessageId());
        } else {
            o.putNull("foundMessageId");
        }
        if (p.getFoundAt() != null) {
            o.put("foundAt", p.getFoundAt().toEpochMilli());
        } else {
            o.putNull("foundAt");
        }
        if (p.getReviewStatus() != null) {
            o.put("reviewStatus", p.getReviewStatus());
        } else {
            o.putNull("reviewStatus");
        }
        o.put("reviewedBy", p.getReviewedBy());
        if (p.getReviewedAt() != null) {
            o.put("reviewedAt", p.getReviewedAt().toEpochMilli());
        } else {
            o.putNull("reviewedAt");
        }
        o.put("createdAt", epochMs(p.getCreatedAt()));
        o.put("updatedAt", epochMs(p.getUpdatedAt()));
        return o;
    }

    @GetMapping(value = "/records/announcements", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> announcements() throws Exception {
        ArrayNode out = mapper.createArrayNode();
        for (Announcement a : announcementRepository.findTop200ByOrderByCreatedAtDesc()) {
            out.add(announcementItem(a));
        }
        return ResponseEntity.ok(mapper.writeValueAsString(out));
    }

    /**
     * One announcement item in exactly the {@code GET /records/announcements}
     * shape — shared with {@link DashboardController}, whose create response
     * must render the new announcement identically.
     */
    ObjectNode announcementItem(Announcement a) {
        ObjectNode o = mapper.createObjectNode();
        o.put("id", a.getId());
        o.put("title", a.getTitle());
        o.put("body", a.getBody());
        o.put("priority", a.getPriority());
        o.put("createdBy", a.getCreatedBy());
        if (a.getExpiresAt() != null) {
            o.put("expiresAt", a.getExpiresAt().toEpochMilli());
        } else {
            o.putNull("expiresAt");
        }
        o.put("createdAt", epochMs(a.getCreatedAt()));
        return o;
    }

    @GetMapping(value = "/records/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> messages() throws Exception {
        ArrayNode out = mapper.createArrayNode();
        for (Message m : messageRepository.findTop200ByOrderByCreatedAtDesc()) {
            ObjectNode o = mapper.createObjectNode();
            o.put("messageId", m.getMessageId());
            o.put("eventType", m.getEventType().name());
            o.put("originDeviceId", m.getOriginDeviceId());
            o.put("createdAt", epochMs(m.getCreatedAt()));
            if (m.getExpiresAt() != null) {
                o.put("expiresAt", m.getExpiresAt().toEpochMilli());
            } else {
                o.putNull("expiresAt");
            }
            o.put("priority", m.getPriority());
            o.put("hopLimit", m.getHopLimit());
            o.put("hopCount", m.getHopCount());
            o.set("hopTrace", parseStored(m.getHopTrace(), mapper.createArrayNode()));
            o.set("payload", parseStored(m.getPayload(), mapper.createObjectNode()));
            o.put("signature", m.getSignature());
            out.add(o);
        }
        return ResponseEntity.ok(mapper.writeValueAsString(out));
    }

    private JsonNode parseStored(String jsonText, JsonNode fallback) {
        if (jsonText == null || jsonText.isBlank()) {
            return fallback;
        }
        try {
            return mapper.readTree(jsonText);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long epochMs(Instant instant) {
        return instant == null ? 0L : instant.toEpochMilli();
    }
}
