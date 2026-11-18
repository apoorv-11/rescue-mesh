package com.rescuemesh.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import com.rescuemesh.server.sync.CanonicalJson;
import com.rescuemesh.server.sync.Ed25519Support;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Photo gateway + V3 case link (photo transfer design §3), on the H2 test
 * profile with a pinned clock (same reason as {@link SyncFlowTests}:
 * fixtures are created around 1700000000000 and must not read as expired).
 *
 * <p>Photos live in {@code target/test-photos} for this class only (the
 * {@code rescuemesh.photos.dir} property override), wiped before the run.
 */
@SpringBootTest(properties = "rescuemesh.photos.dir=target/test-photos")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestMailConfiguration.class, PhotoFlowTests.FixedClockConfiguration.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PhotoFlowTests {

    static final long FIXED_NOW = 1700001000000L;
    static final long T0 = 1700000000000L;
    static final long TTL_72H = 72L * 60 * 60 * 1000;
    static final Path PHOTO_DIR = Path.of("target/test-photos");

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.ofEpochMilli(FIXED_NOW), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestMailConfiguration.CapturingMailService mail;

    private final ObjectMapper mapper = new ObjectMapper();

    /** State shared across the ordered link tests (case 1). */
    private static String case1Token;
    private static KeyPair case1MissingOrigin;
    private static KeyPair case1FoundOrigin;
    private static String case1MissingId;
    private static JsonNode case1MissingEnvelope;
    private static String case1MissingPub;
    private static String case1FoundId;
    private static JsonNode case1FoundEnvelope;
    private static String case1FoundPub;

    @BeforeAll
    static void cleanPhotoDir() throws Exception {
        if (Files.exists(PHOTO_DIR)) {
            try (var walk = Files.walk(PHOTO_DIR)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // best-effort cleanup of a scratch dir
                    }
                });
            }
        }
    }

    @Test
    @Order(1)
    void photoUploadRoundTripAndIdempotentReupload() throws Exception {
        String token = registerVerifyAndLogin("photouser1", "photo1@example.org");
        KeyPair origin = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String pub = pubB64(origin);
        String missingId = UUID.randomUUID().toString();
        byte[] photo = photoBytes(8192, 42);

        ObjectNode payload = missingPayload("Asha Verma");
        payload.set("photoRef", photoRef(missingId, photo));
        JsonNode response = postBatch(token,
                signedEnvelope(origin, missingId, "MISSING_PERSON", payload, T0, T0 + TTL_72H),
                pub, deviceId(origin));
        assertEquals("ACCEPTED", response.get("results").get(0).get("status").textValue());

        // Case view before upload: photoRef present, nothing stored yet.
        JsonNode before = missingCase(token, missingId);
        assertEquals("OPEN", before.get("status").textValue());
        assertEquals(8192, before.get("photoRef").get("sizeBytes").longValue());
        assertFalse(before.get("hasPhoto").booleanValue());
        assertTrue(before.get("foundMessageId").isNull());

        // Upload the matching bytes -> STORED.
        mockMvc.perform(post("/sync/photo")
                        .param("messageId", missingId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.IMAGE_JPEG)
                        .content(photo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value(missingId))
                .andExpect(jsonPath("$.status").value("STORED"))
                .andExpect(jsonPath("$.sizeBytes").value(8192));

        // Download returns the identical bytes as image/jpeg.
        MvcResult download = mockMvc.perform(get("/records/photos/" + missingId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andReturn();
        assertArrayEquals(photo, download.getResponse().getContentAsByteArray());

        // Case view now reports the stored photo.
        JsonNode after = missingCase(token, missingId);
        assertTrue(after.get("hasPhoto").booleanValue());

        // Identical re-upload -> ALREADY_EXISTS, still 200.
        mockMvc.perform(post("/sync/photo")
                        .param("messageId", missingId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.IMAGE_JPEG)
                        .content(photo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALREADY_EXISTS"));
    }

    @Test
    @Order(2)
    void hashMismatchIsRejectedAndNothingStored() throws Exception {
        String token = registerVerifyAndLogin("photouser2", "photo2@example.org");
        KeyPair origin = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String missingId = UUID.randomUUID().toString();
        byte[] realPhoto = photoBytes(4096, 7);
        byte[] impostor = photoBytes(4096, 8); // same size, different bytes

        ObjectNode payload = missingPayload("Ravi Kumar");
        payload.set("photoRef", photoRef(missingId, realPhoto));
        JsonNode response = postBatch(token,
                signedEnvelope(origin, missingId, "MISSING_PERSON", payload, T0, T0 + TTL_72H),
                pubB64(origin), deviceId(origin));
        assertEquals("ACCEPTED", response.get("results").get(0).get("status").textValue());

        mockMvc.perform(post("/sync/photo")
                        .param("messageId", missingId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.IMAGE_JPEG)
                        .content(impostor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("sha256 mismatch"));

        assertFalse(Files.exists(PHOTO_DIR.resolve(missingId + ".jpg")),
                "a rejected upload must not leave a file behind");
        mockMvc.perform(get("/records/photos/" + missingId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(3)
    void sizeMismatchIsRejectedAndNothingStored() throws Exception {
        String token = registerVerifyAndLogin("photouser3", "photo3@example.org");
        KeyPair origin = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String missingId = UUID.randomUUID().toString();
        byte[] photo = photoBytes(4096, 11);

        ObjectNode payload = missingPayload("Meena Devi");
        payload.set("photoRef", photoRef(missingId, photo));
        postBatch(token,
                signedEnvelope(origin, missingId, "MISSING_PERSON", payload, T0, T0 + TTL_72H),
                pubB64(origin), deviceId(origin));

        byte[] truncated = new byte[4095];
        System.arraycopy(photo, 0, truncated, 0, truncated.length);
        mockMvc.perform(post("/sync/photo")
                        .param("messageId", missingId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.IMAGE_JPEG)
                        .content(truncated))
                .andExpect(status().isBadRequest());

        assertFalse(Files.exists(PHOTO_DIR.resolve(missingId + ".jpg")));
        mockMvc.perform(get("/records/photos/" + missingId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(4)
    void unknownMessageId404AndMissingPhotoRef400() throws Exception {
        String token = registerVerifyAndLogin("photouser4", "photo4@example.org");

        String unknownId = UUID.randomUUID().toString();
        mockMvc.perform(post("/sync/photo")
                        .param("messageId", unknownId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.IMAGE_JPEG)
                        .content(photoBytes(128, 3)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/records/photos/" + unknownId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        // A synced record without a photoRef refuses photo uploads.
        KeyPair origin = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String missingId = UUID.randomUUID().toString();
        postBatch(token,
                signedEnvelope(origin, missingId, "MISSING_PERSON",
                        missingPayload("No Photo"), T0, T0 + TTL_72H),
                pubB64(origin), deviceId(origin));
        mockMvc.perform(post("/sync/photo")
                        .param("messageId", missingId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.IMAGE_JPEG)
                        .content(photoBytes(128, 3)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("message has no photoRef"));
    }

    @Test
    @Order(5)
    void photoEndpointsRequireAuthentication() throws Exception {
        String anyId = UUID.randomUUID().toString();
        mockMvc.perform(post("/sync/photo")
                        .param("messageId", anyId)
                        .contentType(MediaType.IMAGE_JPEG)
                        .content(photoBytes(16, 1)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/records/photos/" + anyId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(6)
    void foundUpdateLinksCaseAfterMissingSynced() throws Exception {
        case1Token = registerVerifyAndLogin("caseuser1", "case1@example.org");
        case1MissingOrigin = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        case1FoundOrigin = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        case1MissingId = UUID.randomUUID().toString();
        case1FoundId = UUID.randomUUID().toString();
        case1MissingPub = pubB64(case1MissingOrigin);
        case1FoundPub = pubB64(case1FoundOrigin);
        case1MissingEnvelope = signedEnvelope(case1MissingOrigin, case1MissingId,
                "MISSING_PERSON", missingPayload("Asha Verma"), T0, T0 + TTL_72H);
        ObjectNode foundPayload = mapper.createObjectNode();
        foundPayload.put("caseId", case1MissingId);
        foundPayload.put("note", "Seen at the relief camp");
        case1FoundEnvelope = signedEnvelope(case1FoundOrigin, case1FoundId,
                "PERSON_FOUND_UPDATE", foundPayload, T0 + 60000, T0 + TTL_72H);

        JsonNode missingResponse = postBatch(case1Token, case1MissingEnvelope,
                case1MissingPub, deviceId(case1MissingOrigin));
        assertEquals("ACCEPTED", missingResponse.get("results").get(0).get("status").textValue());
        JsonNode open = missingCase(case1Token, case1MissingId);
        assertEquals("OPEN", open.get("status").textValue());
        assertTrue(open.get("foundMessageId").isNull());
        assertTrue(open.get("foundAt").isNull());

        JsonNode foundResponse = postBatch(case1Token, case1FoundEnvelope,
                case1FoundPub, deviceId(case1FoundOrigin));
        assertEquals("ACCEPTED", foundResponse.get("results").get(0).get("status").textValue());

        JsonNode linked = missingCase(case1Token, case1MissingId);
        assertEquals("FOUND_REPORTED", linked.get("status").textValue());
        assertEquals(case1FoundId, linked.get("foundMessageId").textValue());
        assertEquals(T0 + 60000, linked.get("foundAt").longValue());

        // Re-syncing both envelopes (ALREADY_EXISTS) keeps the link intact.
        JsonNode missingAgain = postBatch(case1Token, case1MissingEnvelope,
                case1MissingPub, deviceId(case1MissingOrigin));
        assertEquals("ALREADY_EXISTS",
                missingAgain.get("results").get(0).get("status").textValue());
        JsonNode foundAgain = postBatch(case1Token, case1FoundEnvelope,
                case1FoundPub, deviceId(case1FoundOrigin));
        assertEquals("ALREADY_EXISTS",
                foundAgain.get("results").get(0).get("status").textValue());
        JsonNode stillLinked = missingCase(case1Token, case1MissingId);
        assertEquals("FOUND_REPORTED", stillLinked.get("status").textValue());
        assertEquals(case1FoundId, stillLinked.get("foundMessageId").textValue());
        assertEquals(T0 + 60000, stillLinked.get("foundAt").longValue());
    }

    @Test
    @Order(7)
    void newestFoundUpdateWinsAndOlderResyncDoesNotMoveLinkBack() throws Exception {
        // A second, NEWER found update for case 1 replaces the link...
        String newerFoundId = UUID.randomUUID().toString();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("caseId", case1MissingId);
        payload.put("note", "Confirmed at the camp office");
        JsonNode newerEnvelope = signedEnvelope(case1FoundOrigin, newerFoundId,
                "PERSON_FOUND_UPDATE", payload, T0 + 120000, T0 + TTL_72H);
        JsonNode response = postBatch(case1Token, newerEnvelope,
                case1FoundPub, deviceId(case1FoundOrigin));
        assertEquals("ACCEPTED", response.get("results").get(0).get("status").textValue());

        JsonNode linked = missingCase(case1Token, case1MissingId);
        assertEquals(newerFoundId, linked.get("foundMessageId").textValue());
        assertEquals(T0 + 120000, linked.get("foundAt").longValue());

        // ...and re-syncing the OLDER update (ALREADY_EXISTS path) must not
        // drag the link back to it.
        postBatch(case1Token, case1FoundEnvelope, case1FoundPub, deviceId(case1FoundOrigin));
        JsonNode stillNewest = missingCase(case1Token, case1MissingId);
        assertEquals(newerFoundId, stillNewest.get("foundMessageId").textValue());
        assertEquals(T0 + 120000, stillNewest.get("foundAt").longValue());
    }

    @Test
    @Order(8)
    void foundUpdateSyncedBeforeMissingStillLinks() throws Exception {
        String token = registerVerifyAndLogin("caseuser2", "case2@example.org");
        KeyPair missingOrigin = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair foundOrigin = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String missingId = UUID.randomUUID().toString();
        String foundId = UUID.randomUUID().toString();

        ObjectNode foundPayload = mapper.createObjectNode();
        foundPayload.put("caseId", missingId);
        foundPayload.put("note", "Reported before the case itself synced");
        JsonNode foundResponse = postBatch(token,
                signedEnvelope(foundOrigin, foundId, "PERSON_FOUND_UPDATE",
                        foundPayload, T0 + 30000, T0 + TTL_72H),
                pubB64(foundOrigin), deviceId(foundOrigin));
        // The found update itself syncs fine — the missing case is not an error.
        assertEquals("ACCEPTED", foundResponse.get("results").get(0).get("status").textValue());

        JsonNode missingResponse = postBatch(token,
                signedEnvelope(missingOrigin, missingId, "MISSING_PERSON",
                        missingPayload("Kabir Singh"), T0, T0 + TTL_72H),
                pubB64(missingOrigin), deviceId(missingOrigin));
        assertEquals("ACCEPTED", missingResponse.get("results").get(0).get("status").textValue());

        JsonNode linked = missingCase(token, missingId);
        assertEquals("FOUND_REPORTED", linked.get("status").textValue());
        assertEquals(foundId, linked.get("foundMessageId").textValue());
        assertEquals(T0 + 30000, linked.get("foundAt").longValue());
    }

    // -- helpers -----------------------------------------------------------

    private ObjectNode missingPayload(String name) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("name", name);
        payload.put("description", "Last seen near the river bridge");
        payload.put("lastSeenArea", "Sector 4");
        return payload;
    }

    private ObjectNode photoRef(String messageId, byte[] photo) throws Exception {
        ObjectNode ref = mapper.createObjectNode();
        ref.put("photoId", messageId);
        ref.put("sha256", HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(photo)));
        ref.put("sizeBytes", photo.length);
        ref.put("mime", "image/jpeg");
        return ref;
    }

    /** Deterministic stand-in JPEG bytes (the server treats photos as opaque). */
    private static byte[] photoBytes(int size, int seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF; // JPEG SOI marker, for realism
        return bytes;
    }

    /** Builds and origin-signs an envelope exactly as the Android app would. */
    private ObjectNode signedEnvelope(KeyPair origin, String messageId, String eventType,
                                      ObjectNode payload, long createdAt, long expiresAt)
            throws Exception {
        ObjectNode signedSet = mapper.createObjectNode();
        signedSet.put("messageId", messageId);
        signedSet.put("eventType", eventType);
        signedSet.put("originDeviceId", deviceId(origin));
        signedSet.put("createdAt", createdAt);
        signedSet.put("expiresAt", expiresAt);
        signedSet.put("priority", 2);
        signedSet.put("hopLimit", 8);
        signedSet.set("payload", payload);
        String signature = Ed25519Support.b64UrlEncode(Ed25519Support.sign(
                origin.getPrivate(), CanonicalJson.canonicalBytes(signedSet)));

        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("messageId", messageId);
        envelope.put("eventType", eventType);
        envelope.put("originDeviceId", deviceId(origin));
        envelope.put("createdAt", createdAt);
        envelope.put("expiresAt", expiresAt);
        envelope.put("priority", 2);
        envelope.put("hopLimit", 8);
        envelope.put("hopCount", 0);
        envelope.set("hopTrace", mapper.createArrayNode());
        envelope.set("payload", payload);
        envelope.put("signature", signature);
        return envelope;
    }

    private JsonNode missingCase(String token, String messageId) throws Exception {
        MvcResult result = mockMvc.perform(get("/records/missing")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode node : mapper.readTree(result.getResponse().getContentAsString())) {
            if (messageId.equals(node.get("messageId").textValue())) {
                return node;
            }
        }
        throw new AssertionError("no /records/missing case for messageId " + messageId);
    }

    private JsonNode postBatch(String token, JsonNode envelope, String originPublicKey,
                               String uploaderDeviceId) throws Exception {
        ObjectNode item = mapper.createObjectNode();
        item.set("envelope", envelope);
        item.put("originPublicKey", originPublicKey);
        ArrayNode items = mapper.createArrayNode();
        items.add(item);
        ObjectNode body = mapper.createObjectNode();
        body.put("deviceId", uploaderDeviceId);
        body.set("items", items);
        MvcResult result = mockMvc.perform(post("/sync/batch")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private static String pubB64(KeyPair origin) {
        return Ed25519Support.b64UrlEncode(Ed25519Support.rawFromPublicKey(origin.getPublic()));
    }

    private static String deviceId(KeyPair origin) {
        return Ed25519Support.deviceIdFor(Ed25519Support.rawFromPublicKey(origin.getPublic()));
    }

    /** Register -> verify (code from the capturing mail service) -> returns the token. */
    private String registerVerifyAndLogin(String username, String email) throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + email
                                + "\",\"password\":\"secret123\"}"))
                .andExpect(status().isCreated());
        String code = mail.lastVerificationCode(email);
        MvcResult result = mockMvc.perform(post("/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }
}
