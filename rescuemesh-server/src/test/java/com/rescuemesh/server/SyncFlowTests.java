package com.rescuemesh.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import com.rescuemesh.server.repository.SyncAuditRepository;
import com.rescuemesh.server.sync.CanonicalJson;
import com.rescuemesh.server.sync.Ed25519Support;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
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
 * Prompt 7 sync API end to end (contracts §5), on the H2 test profile.
 *
 * <p>The server clock is pinned to just after the §3 vector's createdAt
 * (1700000000000): the vector was created in Nov 2023, so under the real 2026
 * clock its expiresAt is in the past and check 4 would — correctly — reject
 * it as expired. Pinning the clock exercises the real pipeline (including the
 * future/expired checks, see {@link #futureCreatedAtIsRejected}) with the
 * fixed cross-side fixture. Production keeps the system clock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestMailConfiguration.class, SyncFlowTests.FixedClockConfiguration.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SyncFlowTests {

    static final long FIXED_NOW = 1700001000000L;

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.ofEpochMilli(FIXED_NOW), ZoneOffset.UTC);
        }
    }

    private static final String VECTOR_PUB = "A6EHv_POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg";
    private static final String VECTOR_DEVICE =
            "56475aa75463474c0285df5dbf2bcab73da651358839e9b77481b2eab107708c";
    private static final String VECTOR_MESSAGE_ID = "12345678-1234-4234-9234-1234567890ab";
    private static final String VECTOR_ENVELOPE =
            "{\"messageId\":\"12345678-1234-4234-9234-1234567890ab\",\"eventType\":\"SOS\","
                    + "\"originDeviceId\":\"56475aa75463474c0285df5dbf2bcab73da651358839e9b77481b2eab107708c\","
                    + "\"createdAt\":1700000000000,\"expiresAt\":1700086400000,\"priority\":1,\"hopLimit\":8,"
                    + "\"hopCount\":0,\"hopTrace\":[{\"i\":0,\"role\":\"ORIGIN\","
                    + "\"deviceId\":\"56475aa75463474c0285df5dbf2bcab73da651358839e9b77481b2eab107708c\","
                    + "\"pk\":\"A6EHv_POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg\",\"at\":1700000000000,"
                    + "\"prevSig\":\"\",\"sig\":\"que4YgpLPBO7pSi8H1_0qgeH5jx_G0FzKo6_l_DkYutQ03MvV39k7rAJtSO8zrSed7Vud2Q45mLIQcuWkxHeDg\"}],"
                    + "\"payload\":{\"category\":\"MEDICAL\",\"description\":\"Trapped under debris\","
                    + "\"peopleCount\":3,\"injured\":1},"
                    + "\"signature\":\"7BfTxpgGrAvUhFV7OGbpM_-ITV15MWjUAy3Hed6QraGldYNRk2YDWBlv1JkPc_hr3_c0IfQG1uSfmjUUPThrBQ\"}";
    private static final String HOP0_SIG =
            "que4YgpLPBO7pSi8H1_0qgeH5jx_G0FzKo6_l_DkYutQ03MvV39k7rAJtSO8zrSed7Vud2Q45mLIQcuWkxHeDg";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestMailConfiguration.CapturingMailService mail;

    @Autowired
    private SyncAuditRepository syncAuditRepository;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @Order(1)
    void pubkeyIsPublicAndBatchAcceptsVectorWithVerifiableAck() throws Exception {
        // GET /sync/pubkey is public — no token.
        MvcResult pubkeyResult = mockMvc.perform(get("/sync/pubkey"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode pubkey = mapper.readTree(pubkeyResult.getResponse().getContentAsString());
        assertEquals(16, pubkey.get("keyId").textValue().length());
        byte[] serverPub = Ed25519Support.b64UrlDecode(pubkey.get("publicKey").textValue());
        assertEquals(32, serverPub.length);
        assertEquals(Ed25519Support.deviceIdFor(serverPub).substring(0, 16),
                pubkey.get("keyId").textValue());

        String token = registerVerifyAndLogin("syncuser1", "sync1@example.org");

        // POST the §3 vector batch -> ACCEPTED.
        MvcResult result = mockMvc.perform(post("/sync/batch")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(mapper.readTree(VECTOR_ENVELOPE), VECTOR_PUB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverTime").value(FIXED_NOW))
                .andExpect(jsonPath("$.results[0].messageId").value(VECTOR_MESSAGE_ID))
                .andExpect(jsonPath("$.results[0].status").value("ACCEPTED"))
                .andReturn();
        JsonNode ack = mapper.readTree(result.getResponse().getContentAsString())
                .get("results").get(0).get("serverAck");
        assertNotNull(ack);
        assertEquals("ACCEPTED", ack.get("status").textValue());
        assertEquals(FIXED_NOW, ack.get("issuedAt").longValue());

        // The ack verifies against the /sync/pubkey key over the canonical ack set.
        ObjectNode ackSignedSet = mapper.createObjectNode();
        ackSignedSet.put("issuedAt", ack.get("issuedAt").longValue());
        ackSignedSet.put("messageId", VECTOR_MESSAGE_ID);
        ackSignedSet.put("status", "ACCEPTED");
        assertTrue(Ed25519Support.verify(serverPub,
                Ed25519Support.b64UrlDecode(ack.get("signature").textValue()),
                CanonicalJson.canonicalBytes(ackSignedSet)));

        // One SyncAudit row for the batch, with the counts.
        assertTrue(syncAuditRepository.findAll().stream().anyMatch(a ->
                "SYNC_BATCH".equals(a.getAction())
                        && VECTOR_DEVICE.equals(a.getDeviceId())
                        && "accepted=1 alreadyExists=0 rejected=0".equals(a.getDetail())));

        // SOS materialisation: vector payload has no location -> lat/lon null.
        MvcResult sosResult = mockMvc.perform(get("/records/sos")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode sos = findByMessageId(mapper.readTree(sosResult.getResponse().getContentAsString()));
        assertNotNull(sos, "SOS record for the vector message must exist");
        assertTrue(sos.get("latitude").isNull());
        assertTrue(sos.get("longitude").isNull());
        assertEquals("Trapped under debris", sos.get("note").textValue());
        assertEquals("OPEN", sos.get("status").textValue());
        assertEquals(VECTOR_DEVICE, sos.get("originDeviceId").textValue());
    }

    @Test
    @Order(2)
    void duplicateBatchReturnsAlreadyExistsAndMergesNewHop() throws Exception {
        String token = registerVerifyAndLogin("syncuser2", "sync2@example.org");

        // Ensure the vector is stored (status depends on test order — don't assert),
        // then a repeat post is ALREADY_EXISTS.
        postBatch(token, mapper.readTree(VECTOR_ENVELOPE), VECTOR_PUB);
        JsonNode again = postBatch(token, mapper.readTree(VECTOR_ENVELOPE), VECTOR_PUB);
        assertEquals("ALREADY_EXISTS", again.get("results").get(0).get("status").textValue());
        assertNotNull(again.get("results").get(0).get("serverAck"));

        // Variant with one extra VALID relay hop appended -> hop merge.
        KeyPair relay = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] relayPubRaw = Ed25519Support.rawFromPublicKey(relay.getPublic());
        String relayDeviceId = Ed25519Support.deviceIdFor(relayPubRaw);

        ObjectNode hopSignedSet = mapper.createObjectNode();
        hopSignedSet.put("messageId", VECTOR_MESSAGE_ID);
        hopSignedSet.put("i", 1);
        hopSignedSet.put("role", "RELAY");
        hopSignedSet.put("deviceId", relayDeviceId);
        hopSignedSet.put("at", 1700003600000L);
        hopSignedSet.put("prevSig", HOP0_SIG);
        String hopSig = Ed25519Support.b64UrlEncode(Ed25519Support.sign(
                relay.getPrivate(), CanonicalJson.canonicalBytes(hopSignedSet)));

        ObjectNode variant = mapper.readTree(VECTOR_ENVELOPE).deepCopy();
        ObjectNode entry = mapper.createObjectNode();
        entry.put("i", 1);
        entry.put("role", "RELAY");
        entry.put("deviceId", relayDeviceId);
        entry.put("pk", Ed25519Support.b64UrlEncode(relayPubRaw));
        entry.put("at", 1700003600000L);
        entry.put("prevSig", HOP0_SIG);
        entry.put("sig", hopSig);
        ((ArrayNode) variant.get("hopTrace")).add(entry);
        variant.put("hopCount", 1);

        JsonNode merged = postBatch(token, variant, VECTOR_PUB);
        assertEquals("ALREADY_EXISTS", merged.get("results").get(0).get("status").textValue());

        // The stored envelope now carries both entries, sorted, hopCount updated.
        MvcResult messagesResult = mockMvc.perform(get("/records/messages")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode stored = findByMessageId(
                mapper.readTree(messagesResult.getResponse().getContentAsString()));
        assertNotNull(stored);
        assertEquals(1, stored.get("hopCount").intValue());
        assertEquals(2, stored.get("hopTrace").size());
        assertEquals(VECTOR_DEVICE, stored.get("hopTrace").get(0).get("deviceId").textValue());
        assertEquals(relayDeviceId, stored.get("hopTrace").get(1).get("deviceId").textValue());
        assertEquals(1, stored.get("hopTrace").get(1).get("i").intValue());
        assertEquals("SOS", stored.get("eventType").textValue());
        assertEquals(1700000000000L, stored.get("createdAt").longValue());
    }

    @Test
    @Order(3)
    void tamperedSignatureIsRejected() throws Exception {
        String token = registerVerifyAndLogin("syncuser3", "sync3@example.org");
        ObjectNode tampered = mapper.readTree(VECTOR_ENVELOPE).deepCopy();
        String sig = tampered.get("signature").textValue();
        tampered.put("signature", (sig.charAt(0) == 'A' ? 'B' : 'A') + sig.substring(1));
        JsonNode response = postBatch(token, tampered, VECTOR_PUB);
        assertEquals("REJECTED", response.get("results").get(0).get("status").textValue());
        assertEquals("bad-signature", response.get("results").get(0).get("reason").textValue());
    }

    @Test
    @Order(4)
    void wrongOriginPublicKeyIsRejected() throws Exception {
        String token = registerVerifyAndLogin("syncuser4", "sync4@example.org");
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String wrongPub = Ed25519Support.b64UrlEncode(
                Ed25519Support.rawFromPublicKey(other.getPublic()));
        JsonNode response = postBatch(token, mapper.readTree(VECTOR_ENVELOPE), wrongPub);
        assertEquals("REJECTED", response.get("results").get(0).get("status").textValue());
        assertEquals("key-mismatch", response.get("results").get(0).get("reason").textValue());
    }

    @Test
    @Order(5)
    void futureCreatedAtIsRejected() throws Exception {
        String token = registerVerifyAndLogin("syncuser5", "sync5@example.org");
        // A fresh origin keypair + an envelope signed in-test with the server's
        // own canonicaliser — proves the whole pipeline end to end.
        KeyPair origin = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] pubRaw = Ed25519Support.rawFromPublicKey(origin.getPublic());
        String deviceId = Ed25519Support.deviceIdFor(pubRaw);
        long createdAt = FIXED_NOW + 25L * 60 * 60 * 1000; // > serverNow + 24h
        long expiresAt = createdAt + 12L * 60 * 60 * 1000;

        ObjectNode payload = mapper.createObjectNode();
        payload.put("status", "SAFE");
        ObjectNode signedSet = mapper.createObjectNode();
        String messageId = UUID.randomUUID().toString();
        signedSet.put("messageId", messageId);
        signedSet.put("eventType", "SAFETY_CHECKIN");
        signedSet.put("originDeviceId", deviceId);
        signedSet.put("createdAt", createdAt);
        signedSet.put("expiresAt", expiresAt);
        signedSet.put("priority", 3);
        signedSet.put("hopLimit", 8);
        signedSet.set("payload", payload);
        String signature = Ed25519Support.b64UrlEncode(Ed25519Support.sign(
                origin.getPrivate(), CanonicalJson.canonicalBytes(signedSet)));

        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("messageId", messageId);
        envelope.put("eventType", "SAFETY_CHECKIN");
        envelope.put("originDeviceId", deviceId);
        envelope.put("createdAt", createdAt);
        envelope.put("expiresAt", expiresAt);
        envelope.put("priority", 3);
        envelope.put("hopLimit", 8);
        envelope.put("hopCount", 0);
        envelope.set("hopTrace", mapper.createArrayNode());
        envelope.set("payload", payload);
        envelope.put("signature", signature);

        JsonNode response = postBatch(token, envelope, Ed25519Support.b64UrlEncode(pubRaw));
        assertEquals("REJECTED", response.get("results").get(0).get("status").textValue());
        assertEquals("future-createdAt", response.get("results").get(0).get("reason").textValue());
    }

    @Test
    @Order(6)
    void unauthenticatedBatchAndRecordsAre401() throws Exception {
        mockMvc.perform(post("/sync/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(mapper.readTree(VECTOR_ENVELOPE), VECTOR_PUB)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/records/sos"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/records/messages"))
                .andExpect(status().isUnauthorized());
    }

    // -- helpers -----------------------------------------------------------

    private String batchBody(JsonNode envelope, String originPublicKey) throws Exception {
        ObjectNode item = mapper.createObjectNode();
        item.set("envelope", envelope);
        item.put("originPublicKey", originPublicKey);
        ObjectNode body = mapper.createObjectNode();
        body.put("deviceId", VECTOR_DEVICE);
        ArrayNode items = mapper.createArrayNode();
        items.add(item);
        body.set("items", items);
        return mapper.writeValueAsString(body);
    }

    private JsonNode postBatch(String token, JsonNode envelope, String originPublicKey)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/sync/batch")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(envelope, originPublicKey)))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode findByMessageId(JsonNode array) {
        for (JsonNode node : array) {
            if (VECTOR_MESSAGE_ID.equals(node.get("messageId").textValue())) {
                return node;
            }
        }
        return null;
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
