package com.rescuemesh.server.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Sync signing key loading: ephemeral mode, JDK PKCS#8, and OpenSSL-style PKCS#8. */
class SyncSigningKeyTests {

    @Test
    void ephemeralKeySignsAndVerifies() {
        SyncSigningKey key = new SyncSigningKey("");
        byte[] data = "ack".getBytes(StandardCharsets.UTF_8);
        assertTrue(Ed25519Support.verify(key.rawPublicKey(), key.sign(data), data));
        assertEquals(16, key.keyId().length());
        assertEquals(Ed25519Support.deviceIdFor(key.rawPublicKey()).substring(0, 16), key.keyId());
    }

    @Test
    void loadsJdkPkcs8() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String b64 = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        SyncSigningKey key = new SyncSigningKey(b64);
        assertArrayEquals(Ed25519Support.rawFromPublicKey(pair.getPublic()), key.rawPublicKey());
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);
        assertTrue(Ed25519Support.verify(key.rawPublicKey(), key.sign(data), data));
    }

    @Test
    void loadsOpenSslStylePkcs8WithoutPublicKeyField() throws Exception {
        // OpenSSL `pkcs8 -topk8 -nocrypt -outform DER` output for Ed25519 is a
        // 48-byte OneAsymmetricKey WITHOUT the optional [1] public key:
        // SEQ { INT 0, SEQ { OID 1.3.101.112 }, OCTET { OCTET seed32 } }.
        // The loader must derive the public key from the seed (RFC 8032).
        byte[] seed = new byte[32];
        for (int i = 0; i < 32; i++) {
            seed[i] = (byte) i; // the §3 test-vector seed
        }
        byte[] pkcs8 = new byte[48];
        byte[] header = {
                0x30, 0x2E, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70,
                0x04, 0x22, 0x04, 0x20
        };
        System.arraycopy(header, 0, pkcs8, 0, header.length);
        System.arraycopy(seed, 0, pkcs8, header.length, 32);

        SyncSigningKey key = new SyncSigningKey(Base64.getEncoder().encodeToString(pkcs8));
        assertEquals(CanonicalJsonTests.ORIGIN_PUBLIC_KEY, key.publicKeyB64Url());
        byte[] data = "signed".getBytes(StandardCharsets.UTF_8);
        assertTrue(Ed25519Support.verify(key.rawPublicKey(), key.sign(data), data));
    }
}
