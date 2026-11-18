package com.rescuemesh.server.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rescuemesh.server.domain.Announcement;
import com.rescuemesh.server.domain.EventType;
import com.rescuemesh.server.domain.Message;
import com.rescuemesh.server.domain.MissingPerson;
import com.rescuemesh.server.domain.SOSRecord;
import com.rescuemesh.server.domain.SyncAudit;
import com.rescuemesh.server.repository.AnnouncementRepository;
import com.rescuemesh.server.repository.MessageRepository;
import com.rescuemesh.server.repository.MissingPersonRepository;
import com.rescuemesh.server.repository.SOSRecordRepository;
import com.rescuemesh.server.repository.SyncAuditRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code POST /sync/batch} processing, per the batch contracts §5.
 *
 * <p>Per item, checks run <b>in the contract's exact order</b> — structural →
 * key binding → origin signature → future/expired → idempotent insert — and
 * the first failure decides the result. Each item is stored in its own
 * transaction, so one rejected or duplicate item never rolls back the rest of
 * the batch. One {@link SyncAudit} row is written per batch.
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    private static final long FUTURE_TOLERANCE_MS = 24L * 60 * 60 * 1000;

    private final MessageRepository messageRepository;
    private final SOSRecordRepository sosRecordRepository;
    private final MissingPersonRepository missingPersonRepository;
    private final AnnouncementRepository announcementRepository;
    private final SyncAuditRepository syncAuditRepository;
    private final SyncSigningKey signingKey;
    private final RecordsChangedPublisher recordsChangedPublisher;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TransactionTemplate txTemplate;

    public SyncService(MessageRepository messageRepository,
                       SOSRecordRepository sosRecordRepository,
                       MissingPersonRepository missingPersonRepository,
                       AnnouncementRepository announcementRepository,
                       SyncAuditRepository syncAuditRepository,
                       SyncSigningKey signingKey,
                       Clock syncClock,
                       PlatformTransactionManager transactionManager,
                       RecordsChangedPublisher recordsChangedPublisher) {
        this.messageRepository = messageRepository;
        this.sosRecordRepository = sosRecordRepository;
        this.missingPersonRepository = missingPersonRepository;
        this.announcementRepository = announcementRepository;
        this.syncAuditRepository = syncAuditRepository;
        this.signingKey = signingKey;
        this.recordsChangedPublisher = recordsChangedPublisher;
        this.clock = syncClock;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /** Processes one batch request body (already parsed) and builds the response body. */
    public ObjectNode processBatch(JsonNode root) {
        String uploaderDeviceId = root.hasNonNull("deviceId") && root.get("deviceId").isTextual()
                ? root.get("deviceId").textValue() : "";
        long serverNow = clock.millis();

        ArrayNode results = mapper.createArrayNode();
        List<String> acceptedIds = new ArrayList<>();
        int accepted = 0;
        int alreadyExists = 0;
        int rejected = 0;
        for (JsonNode item : root.get("items")) {
            ItemOutcome outcome = processItem(item, serverNow);
            switch (outcome.status()) {
                case "ACCEPTED" -> accepted++;
                case "ALREADY_EXISTS" -> alreadyExists++;
                default -> rejected++;
            }
            if ("ACCEPTED".equals(outcome.status())) {
                acceptedIds.add(outcome.messageId());
            }
            ObjectNode entry = mapper.createObjectNode();
            entry.put("messageId", outcome.messageId());
            entry.put("status", outcome.status());
            if (outcome.reason() != null) {
                entry.put("reason", outcome.reason());
            }
            if ("ACCEPTED".equals(outcome.status()) || "ALREADY_EXISTS".equals(outcome.status())) {
                entry.set("serverAck", buildAck(outcome.messageId(), outcome.status(), serverNow));
            }
            results.add(entry);
        }

        int a = accepted;
        int ae = alreadyExists;
        int r = rejected;
        txTemplate.executeWithoutResult(status -> {
            SyncAudit audit = new SyncAudit();
            audit.setAction("SYNC_BATCH");
            audit.setDeviceId(uploaderDeviceId);
            audit.setDetail("accepted=" + a + " alreadyExists=" + ae + " rejected=" + r);
            syncAuditRepository.save(audit);
        });

        if (!acceptedIds.isEmpty()) {
            recordsChangedPublisher.publishRecordsChanged(acceptedIds, serverNow);
        }

        ObjectNode response = mapper.createObjectNode();
        response.put("serverTime", serverNow);
        response.set("results", results);
        return response;
    }

    private ObjectNode buildAck(String messageId, String status, long issuedAt) {
        ObjectNode signedSet = mapper.createObjectNode();
        signedSet.put("issuedAt", issuedAt);
        signedSet.put("messageId", messageId);
        signedSet.put("status", status);
        String signature = Ed25519Support.b64UrlEncode(
                signingKey.sign(CanonicalJson.canonicalBytes(signedSet)));
        ObjectNode ack = mapper.createObjectNode();
        ack.put("messageId", messageId);
        ack.put("status", status);
        ack.put("issuedAt", issuedAt);
        ack.put("signature", signature);
        return ack;
    }

    private record ItemOutcome(String messageId, String status, String reason) {
        static ItemOutcome rejected(String messageId, String reason) {
            return new ItemOutcome(messageId, "REJECTED", reason);
        }
    }

    /** A structurally valid envelope, ready for the cryptographic checks. */
    private record ParsedEnvelope(
            String messageId, EventType eventType, String originDeviceId,
            long createdAt, long expiresAt, int priority, int hopLimit, int hopCount,
            JsonNode hopTrace, JsonNode payload, String signatureText, byte[] signatureBytes,
            byte[] signedSetCanonicalBytes) {
    }

    private ItemOutcome processItem(JsonNode item, long serverNow) {
        String resultMessageId = "";
        JsonNode envelope = null;
        if (item != null && item.isObject()) {
            envelope = item.get("envelope");
            if (envelope != null && envelope.isObject()
                    && envelope.hasNonNull("messageId") && envelope.get("messageId").isTextual()) {
                resultMessageId = envelope.get("messageId").textValue();
            }
        }

        // -- Check 1: structural -------------------------------------------
        ParsedEnvelope parsed;
        byte[] originPublicKey;
        try {
            if (item == null || !item.isObject() || envelope == null || !envelope.isObject()) {
                return ItemOutcome.rejected(resultMessageId, "malformed");
            }
            if (!item.hasNonNull("originPublicKey") || !item.get("originPublicKey").isTextual()) {
                return ItemOutcome.rejected(resultMessageId, "malformed");
            }
            originPublicKey = Ed25519Support.b64UrlDecode(item.get("originPublicKey").textValue());
            if (originPublicKey.length != 32) {
                return ItemOutcome.rejected(resultMessageId, "malformed");
            }
            parsed = parseEnvelope(envelope);
            if (parsed == null) {
                return ItemOutcome.rejected(resultMessageId, "malformed");
            }
        } catch (IllegalArgumentException e) {
            return ItemOutcome.rejected(resultMessageId, "malformed");
        }

        // -- Check 2: key binding ------------------------------------------
        if (!Ed25519Support.deviceIdFor(originPublicKey).equals(parsed.originDeviceId())) {
            return ItemOutcome.rejected(resultMessageId, "key-mismatch");
        }

        // -- Check 3: origin signature over the §1 signed set ---------------
        if (!Ed25519Support.verify(originPublicKey, parsed.signatureBytes(),
                parsed.signedSetCanonicalBytes())) {
            return ItemOutcome.rejected(resultMessageId, "bad-signature");
        }

        // -- Check 4: future / expired (server clock) ------------------------
        if (parsed.createdAt() > serverNow + FUTURE_TOLERANCE_MS) {
            return ItemOutcome.rejected(resultMessageId, "future-createdAt");
        }
        if (parsed.expiresAt() <= serverNow) {
            return ItemOutcome.rejected(resultMessageId, "expired");
        }

        // -- Check 5: idempotent insert (+ hop merge on duplicate) ----------
        try {
            String status = txTemplate.execute(tx -> insertOrMerge(parsed));
            return new ItemOutcome(parsed.messageId(), status, null);
        } catch (DataIntegrityViolationException race) {
            // Unique-constraint race with a concurrent batch: the row exists now.
            log.info("Sync insert race for messageId={} — falling back to merge", parsed.messageId());
            String status = txTemplate.execute(tx -> insertOrMerge(parsed));
            return new ItemOutcome(parsed.messageId(), status, null);
        }
    }

    /** Runs inside a transaction. Returns ACCEPTED or ALREADY_EXISTS. */
    private String insertOrMerge(ParsedEnvelope parsed) {
        Optional<Message> existing = messageRepository.findByMessageId(parsed.messageId());
        if (existing.isPresent()) {
            Message stored = existing.get();
            mergeHopTrace(stored, parsed);
            messageRepository.save(stored);
            if (parsed.eventType() == EventType.PERSON_FOUND_UPDATE) {
                // Re-applying the case link is idempotent, and it heals the
                // link if the case row appeared after this update first
                // synced (the link itself is best-effort, never an error).
                applyFoundLink(parsed);
            }
            return "ALREADY_EXISTS";
        }
        Message message = new Message();
        message.setMessageId(parsed.messageId());
        message.setEventType(parsed.eventType());
        message.setOriginDeviceId(parsed.originDeviceId());
        message.setCreatedAt(Instant.ofEpochMilli(parsed.createdAt()));
        message.setExpiresAt(Instant.ofEpochMilli(parsed.expiresAt()));
        message.setPriority(parsed.priority());
        message.setHopLimit(parsed.hopLimit());
        message.setHopCount(parsed.hopCount());
        message.setHopTrace(CanonicalJson.canonicalize(parsed.hopTrace()));
        message.setPayload(CanonicalJson.canonicalize(parsed.payload()));
        message.setSignature(parsed.signatureText());
        messageRepository.save(message);
        materialise(parsed);
        return "ACCEPTED";
    }

    /** Per-type projection rows, created only on ACCEPTED (§5 mapping). */
    private void materialise(ParsedEnvelope parsed) {
        JsonNode payload = parsed.payload();
        switch (parsed.eventType()) {
            case SOS -> {
                SOSRecord record = new SOSRecord();
                record.setMessageId(parsed.messageId());
                record.setOriginDeviceId(parsed.originDeviceId());
                record.setStatus(SOSRecord.Status.OPEN);
                JsonNode location = payload.get("location");
                if (location != null && location.isObject()) {
                    record.setLatitude(parseCoordinate(location.get("lat")));
                    record.setLongitude(parseCoordinate(location.get("lon")));
                }
                record.setNote(textOrNull(payload.get("description")));
                record.setCreatedAt(Instant.ofEpochMilli(parsed.createdAt()));
                sosRecordRepository.save(record);
            }
            case MISSING_PERSON -> {
                MissingPerson person = new MissingPerson();
                person.setMessageId(parsed.messageId());
                String name = textOrNull(payload.get("name"));
                person.setName(name == null || name.isBlank() ? "Unknown" : name);
                JsonNode age = payload.get("age");
                person.setAge(age != null && age.isIntegralNumber() ? age.intValue() : null);
                person.setDescription(textOrNull(payload.get("description")));
                person.setLastSeenArea(textOrNull(payload.get("lastSeenArea")));
                JsonNode lastSeenAt = payload.get("lastSeenAt");
                person.setLastSeenAt(lastSeenAt != null && lastSeenAt.isIntegralNumber()
                        ? Instant.ofEpochMilli(lastSeenAt.longValue()) : null);
                person.setReportedByDeviceId(parsed.originDeviceId());
                missingPersonRepository.save(person);
                linkAlreadySyncedFoundUpdates(person);
            }
            case PERSON_FOUND_UPDATE -> applyFoundLink(parsed);
            case ANNOUNCEMENT -> {
                Announcement announcement = new Announcement();
                String category = textOrNull(payload.get("category"));
                announcement.setTitle(category == null ? "" : category);
                String text = textOrNull(payload.get("text"));
                announcement.setBody(text == null ? "" : text);
                announcement.setPriority(parsed.priority());
                announcement.setCreatedBy(parsed.originDeviceId());
                announcement.setExpiresAt(Instant.ofEpochMilli(parsed.expiresAt()));
                announcement.setCreatedAt(Instant.ofEpochMilli(parsed.createdAt()));
                announcementRepository.save(announcement);
            }
            default -> {
                // SAFETY_CHECKIN / RESOURCE_UPDATE: the Message row only —
                // no per-type table exists for them.
            }
        }
    }

    /**
     * V3 case link, forward direction: a PERSON_FOUND_UPDATE names its case
     * by payload {@code caseId} = the MISSING_PERSON envelope's messageId.
     * Best-effort by design — no case row yet (found synced first) is not an
     * error; the reverse direction below links it when the case arrives.
     *
     * <p>Overwrite rule: the link points at the <b>newest</b> found update
     * for the case (by envelope createdAt). An older update re-syncing later
     * never moves the link backwards.
     */
    private void applyFoundLink(ParsedEnvelope foundUpdate) {
        String caseId = textOrNull(foundUpdate.payload().get("caseId"));
        if (caseId == null || caseId.isBlank()) {
            return;
        }
        missingPersonRepository.findByMessageId(caseId).ifPresent(person -> {
            Instant foundAt = Instant.ofEpochMilli(foundUpdate.createdAt());
            if (person.getFoundAt() == null || !foundAt.isBefore(person.getFoundAt())) {
                person.setFoundMessageId(foundUpdate.messageId());
                person.setFoundAt(foundAt);
                person.setStatus(MissingPerson.Status.FOUND_REPORTED);
                missingPersonRepository.save(person);
            }
        });
    }

    /**
     * V3 case link, reverse direction: a found update may already be stored
     * as a Message when its case (MISSING_PERSON) materialises. Scan stored
     * found updates for this case and apply the newest one.
     */
    private void linkAlreadySyncedFoundUpdates(MissingPerson person) {
        messageRepository.findByEventType(EventType.PERSON_FOUND_UPDATE).stream()
                .filter(m -> person.getMessageId().equals(payloadCaseId(m)))
                .max(Comparator.comparing(Message::getCreatedAt))
                .ifPresent(newest -> {
                    person.setFoundMessageId(newest.getMessageId());
                    person.setFoundAt(newest.getCreatedAt());
                    person.setStatus(MissingPerson.Status.FOUND_REPORTED);
                    missingPersonRepository.save(person);
                });
    }

    /** caseId out of a stored Message's payload JSON; null when absent/unreadable. */
    private String payloadCaseId(Message message) {
        try {
            JsonNode payload = mapper.readTree(
                    message.getPayload() == null ? "{}" : message.getPayload());
            return textOrNull(payload.get("caseId"));
        } catch (Exception e) {
            return null;
        }
    }

    private static Double parseCoordinate(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return Double.parseDouble(node.textValue());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    /**
     * Hop merge for ALREADY_EXISTS (§5): union of entries keyed by
     * {@code (i, deviceId)}; the existing entry wins on conflict; only VALID
     * incoming entries (§2 checks) are merged; result sorted by
     * {@code (i, deviceId)}; stored hopCount := merged size − 1.
     */
    private void mergeHopTrace(Message stored, ParsedEnvelope parsed) {
        List<JsonNode> merged = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        try {
            JsonNode storedTrace = mapper.readTree(
                    stored.getHopTrace() == null ? "[]" : stored.getHopTrace());
            if (storedTrace.isArray()) {
                for (JsonNode entry : storedTrace) {
                    merged.add(entry);
                    seen.put(hopKey(entry), Boolean.TRUE);
                }
            }
        } catch (Exception e) {
            log.warn("Stored hopTrace for messageId={} is unparseable; merge starts empty",
                    stored.getMessageId());
        }

        String expectedPrevSig = "";
        boolean changed = false;
        for (JsonNode entry : parsed.hopTrace()) {
            String entrySig = textOrNull(entry.isObject() ? entry.get("sig") : null);
            boolean valid = isValidHopEntry(entry, parsed.messageId(), expectedPrevSig);
            if (entrySig != null) {
                expectedPrevSig = entrySig;
            }
            if (valid && !seen.containsKey(hopKey(entry))) {
                merged.add(entry);
                seen.put(hopKey(entry), Boolean.TRUE);
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        merged.sort(Comparator
                .comparingInt((JsonNode e) -> e.get("i").intValue())
                .thenComparing(e -> e.get("deviceId").textValue()));
        ArrayNode mergedArray = mapper.createArrayNode();
        merged.forEach(mergedArray::add);
        stored.setHopTrace(CanonicalJson.canonicalize(mergedArray));
        stored.setHopCount(merged.size() - 1);
    }

    private static String hopKey(JsonNode entry) {
        JsonNode i = entry.get("i");
        JsonNode deviceId = entry.get("deviceId");
        return (i != null && i.isIntegralNumber() ? i.longValue() : "?")
                + "|" + (deviceId != null && deviceId.isTextual() ? deviceId.textValue() : "?");
    }

    /**
     * §2 hop-entry validity: deviceId == hex(SHA-256(raw(pk))), sig verifies
     * under pk over the entry's signed set, and prevSig chains to the previous
     * entry's sig. Invalid entries are flagged-not-fatal: callers simply do
     * not merge them.
     */
    private boolean isValidHopEntry(JsonNode entry, String messageId, String expectedPrevSig) {
        try {
            if (entry == null || !entry.isObject()) {
                return false;
            }
            JsonNode i = entry.get("i");
            JsonNode role = entry.get("role");
            JsonNode deviceId = entry.get("deviceId");
            JsonNode pk = entry.get("pk");
            JsonNode at = entry.get("at");
            JsonNode prevSig = entry.get("prevSig");
            JsonNode sig = entry.get("sig");
            if (i == null || !i.isIntegralNumber()
                    || role == null || !role.isTextual()
                    || !(role.textValue().equals("ORIGIN") || role.textValue().equals("RELAY")
                            || role.textValue().equals("GATEWAY"))
                    || deviceId == null || !deviceId.isTextual()
                    || pk == null || !pk.isTextual()
                    || at == null || !at.isIntegralNumber()
                    || prevSig == null || !prevSig.isTextual()
                    || sig == null || !sig.isTextual()) {
                return false;
            }
            if (!prevSig.textValue().equals(expectedPrevSig)) {
                return false;
            }
            byte[] pkBytes = Ed25519Support.b64UrlDecode(pk.textValue());
            byte[] sigBytes = Ed25519Support.b64UrlDecode(sig.textValue());
            if (pkBytes.length != 32 || sigBytes.length != 64) {
                return false;
            }
            if (!Ed25519Support.deviceIdFor(pkBytes).equals(deviceId.textValue())) {
                return false;
            }
            ObjectNode signedSet = mapper.createObjectNode();
            signedSet.put("messageId", messageId);
            signedSet.set("i", i);
            signedSet.set("role", role);
            signedSet.set("deviceId", deviceId);
            signedSet.set("at", at);
            signedSet.set("prevSig", prevSig);
            JsonNode loc = entry.get("loc");
            if (loc != null) {
                if (!loc.isTextual()) {
                    return false;
                }
                signedSet.set("loc", loc);
            }
            JsonNode net = entry.get("net");
            if (net != null) {
                if (!net.isTextual()) {
                    return false;
                }
                signedSet.set("net", net);
            }
            return Ed25519Support.verify(pkBytes, sigBytes, CanonicalJson.canonicalBytes(signedSet));
        } catch (Exception e) {
            return false;
        }
    }

    /** Structural validation (§5 check 1). Returns null when malformed. */
    private ParsedEnvelope parseEnvelope(JsonNode envelope) {
        JsonNode messageId = envelope.get("messageId");
        JsonNode eventTypeNode = envelope.get("eventType");
        JsonNode originDeviceId = envelope.get("originDeviceId");
        JsonNode createdAt = envelope.get("createdAt");
        JsonNode expiresAt = envelope.get("expiresAt");
        JsonNode priority = envelope.get("priority");
        JsonNode hopLimit = envelope.get("hopLimit");
        JsonNode hopCount = envelope.get("hopCount");
        JsonNode hopTrace = envelope.get("hopTrace");
        JsonNode payload = envelope.get("payload");
        JsonNode signature = envelope.get("signature");

        if (messageId == null || !messageId.isTextual() || messageId.textValue().isBlank()
                || eventTypeNode == null || !eventTypeNode.isTextual()
                || originDeviceId == null || !originDeviceId.isTextual()
                || !isIntegral(createdAt) || !isIntegral(expiresAt)
                || !isIntegral(priority) || !isIntegral(hopLimit) || !isIntegral(hopCount)
                || hopTrace == null || !hopTrace.isArray()
                || payload == null || !payload.isObject()
                || signature == null || !signature.isTextual()) {
            return null;
        }
        EventType eventType;
        try {
            eventType = EventType.valueOf(eventTypeNode.textValue());
        } catch (IllegalArgumentException e) {
            return null;
        }
        long priorityValue = priority.longValue();
        long hopLimitValue = hopLimit.longValue();
        long hopCountValue = hopCount.longValue();
        if (priorityValue < 1 || priorityValue > 3
                || hopCountValue < 0 || hopLimitValue < 0 || hopCountValue > hopLimitValue
                || hopLimitValue > Integer.MAX_VALUE || hopCountValue > Integer.MAX_VALUE) {
            return null;
        }
        byte[] signatureBytes;
        try {
            signatureBytes = Ed25519Support.b64UrlDecode(signature.textValue());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (signatureBytes.length != 64) {
            return null;
        }
        // The §1 signed set — also proves payload/hopTrace contain no floats
        // (canonicalisation rejects non-integral numbers, like the Android parser).
        ObjectNode signedSet = mapper.createObjectNode();
        signedSet.set("messageId", messageId);
        signedSet.set("eventType", eventTypeNode);
        signedSet.set("originDeviceId", originDeviceId);
        signedSet.set("createdAt", createdAt);
        signedSet.set("expiresAt", expiresAt);
        signedSet.set("priority", priority);
        signedSet.set("hopLimit", hopLimit);
        signedSet.set("payload", payload);
        byte[] canonicalBytes;
        try {
            canonicalBytes = CanonicalJson.canonicalBytes(signedSet);
            CanonicalJson.canonicalize(hopTrace);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return new ParsedEnvelope(
                messageId.textValue(), eventType, originDeviceId.textValue(),
                createdAt.longValue(), expiresAt.longValue(),
                (int) priorityValue, (int) hopLimitValue, (int) hopCountValue,
                hopTrace, payload, signature.textValue(), signatureBytes, canonicalBytes);
    }

    private static boolean isIntegral(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong();
    }
}
