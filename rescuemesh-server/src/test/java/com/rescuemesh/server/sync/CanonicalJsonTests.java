package com.rescuemesh.server.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The Java canonicaliser must be byte-identical to Android's CanonicalJson.
 * Proven against the fixed cross-side test vector (batch contracts §3),
 * hard-coded here exactly as specified.
 */
class CanonicalJsonTests {

    static final String ORIGIN_PUBLIC_KEY = "A6EHv_POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg";
    static final String ORIGIN_DEVICE_ID =
            "56475aa75463474c0285df5dbf2bcab73da651358839e9b77481b2eab107708c";
    static final String MESSAGE_ID = "12345678-1234-4234-9234-1234567890ab";

    static final String CANONICAL_ENVELOPE =
            "{\"createdAt\":1700000000000,\"eventType\":\"SOS\",\"expiresAt\":1700086400000,"
                    + "\"hopLimit\":8,\"messageId\":\"12345678-1234-4234-9234-1234567890ab\","
                    + "\"originDeviceId\":\"56475aa75463474c0285df5dbf2bcab73da651358839e9b77481b2eab107708c\","
                    + "\"payload\":{\"category\":\"MEDICAL\",\"description\":\"Trapped under debris\","
                    + "\"injured\":1,\"peopleCount\":3},\"priority\":1}";

    static final String ENVELOPE_SIGNATURE =
            "7BfTxpgGrAvUhFV7OGbpM_-ITV15MWjUAy3Hed6QraGldYNRk2YDWBlv1JkPc_hr3_c0IfQG1uSfmjUUPThrBQ";

    static final String CANONICAL_HOP0 =
            "{\"at\":1700000000000,\"deviceId\":\"56475aa75463474c0285df5dbf2bcab73da651358839e9b77481b2eab107708c\","
                    + "\"i\":0,\"messageId\":\"12345678-1234-4234-9234-1234567890ab\","
                    + "\"prevSig\":\"\",\"role\":\"ORIGIN\"}";

    static final String HOP0_SIGNATURE =
            "que4YgpLPBO7pSi8H1_0qgeH5jx_G0FzKo6_l_DkYutQ03MvV39k7rAJtSO8zrSed7Vud2Q45mLIQcuWkxHeDg";

    static final String FULL_ENVELOPE =
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

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void canonicalisingSignedSetYieldsVectorCanonicalString() throws Exception {
        JsonNode envelope = mapper.readTree(FULL_ENVELOPE);
        ObjectNode signedSet = mapper.createObjectNode();
        for (String field : new String[]{"messageId", "eventType", "originDeviceId", "createdAt",
                "expiresAt", "priority", "hopLimit", "payload"}) {
            signedSet.set(field, envelope.get(field));
        }
        assertEquals(CANONICAL_ENVELOPE, CanonicalJson.canonicalize(signedSet));
        assertArrayEquals(CANONICAL_ENVELOPE.getBytes(StandardCharsets.UTF_8),
                CanonicalJson.canonicalBytes(signedSet));
    }

    @Test
    void vectorEnvelopeSignatureVerifiesUnderJdkEd25519() {
        byte[] pub = Ed25519Support.b64UrlDecode(ORIGIN_PUBLIC_KEY);
        byte[] sig = Ed25519Support.b64UrlDecode(ENVELOPE_SIGNATURE);
        assertTrue(Ed25519Support.verify(pub, sig,
                CANONICAL_ENVELOPE.getBytes(StandardCharsets.UTF_8)));
        assertEquals(ORIGIN_DEVICE_ID, Ed25519Support.deviceIdFor(pub));
    }

    @Test
    void canonicalisingHop0YieldsVectorCanonicalStringAndVerifies() throws Exception {
        JsonNode envelope = mapper.readTree(FULL_ENVELOPE);
        JsonNode entry0 = envelope.get("hopTrace").get(0);
        ObjectNode signedSet = mapper.createObjectNode();
        signedSet.set("messageId", envelope.get("messageId"));
        for (String field : new String[]{"i", "role", "deviceId", "at", "prevSig"}) {
            signedSet.set(field, entry0.get(field));
        }
        assertEquals(CANONICAL_HOP0, CanonicalJson.canonicalize(signedSet));
        byte[] pub = Ed25519Support.b64UrlDecode(ORIGIN_PUBLIC_KEY);
        assertTrue(Ed25519Support.verify(pub, Ed25519Support.b64UrlDecode(HOP0_SIGNATURE),
                CANONICAL_HOP0.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void seedDerivesVectorPublicKey() {
        byte[] seed = new byte[32];
        for (int i = 0; i < 32; i++) {
            seed[i] = (byte) i;
        }
        byte[] pub = Ed25519Support.derivePublicKey(seed);
        assertEquals(ORIGIN_PUBLIC_KEY, Ed25519Support.b64UrlEncode(pub));
        assertEquals(ORIGIN_DEVICE_ID, Ed25519Support.deviceIdFor(pub));
    }

    @Test
    void nonIntegralNumbersAreRejected() throws Exception {
        JsonNode node = mapper.readTree("{\"x\":1.5}");
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.canonicalize(node));
    }

    @Test
    void stringEscapingMatchesAndroidRules() throws Exception {
        // Short forms for \b \t \n \f \r, \" and \\; unicode-escape lowercase for other
        // C0 controls; non-ASCII raw. (Input built via parse so escapes decode first.)
        JsonNode node = mapper.readTree("{\"s\":\"a\\u0001b\\u00e9\\n\"}");
        assertEquals("{\"s\":\"a\\u0001bé\\n\"}", CanonicalJson.canonicalize(node));
    }
}
