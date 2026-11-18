package com.rescuemesh.server.sync;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The server's sync signing key: signs the {@code serverAck} objects returned
 * by {@code POST /sync/batch} (and gossiped in HELLO packets), so a phone can
 * prove the server really accepted a record.
 *
 * <p>Configured via env {@code SYNC_SIGNING_KEY} (property
 * {@code rescuemesh.sync.signing-key}): standard Base64 of a PKCS#8 DER
 * Ed25519 private key. When unset/blank, an <b>ephemeral</b> keypair is
 * generated at startup — fine for local development, but acks signed before a
 * restart will not verify afterwards, so a real deployment must set the env
 * var (see README for how to generate one).
 *
 * <p>{@code keyId} = first 16 hex characters of hex(SHA-256(raw public key)).
 */
@Component
public class SyncSigningKey {

    private static final Logger log = LoggerFactory.getLogger(SyncSigningKey.class);

    private final PrivateKey privateKey;
    private final byte[] rawPublicKey;
    private final String keyId;

    public SyncSigningKey(@Value("${rescuemesh.sync.signing-key:}") String configuredKey) {
        try {
            if (configuredKey == null || configuredKey.isBlank()) {
                KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                this.privateKey = pair.getPrivate();
                this.rawPublicKey = Ed25519Support.rawFromPublicKey(pair.getPublic());
                log.warn("SYNC_SIGNING_KEY is not set — using an EPHEMERAL sync signing key "
                        + "(dev mode: server acks will not verify across restarts). "
                        + "Set SYNC_SIGNING_KEY for any real deployment.");
            } else {
                byte[] pkcs8 = Base64.getDecoder().decode(configuredKey.trim());
                PrivateKey priv = KeyFactory.getInstance("Ed25519")
                        .generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
                this.privateKey = priv;
                // The JDK cannot derive an Ed25519 public key from the private
                // key object. Use the PKCS#8 [1] field when present (JDK
                // encodings include it); otherwise derive the public key from
                // the seed (OpenSSL's PKCS#8 omits [1]) per RFC 8032 §5.1.5.
                byte[] fromField = tryExtractPublicKeyFromPkcs8(pkcs8);
                this.rawPublicKey = fromField != null
                        ? fromField
                        : Ed25519Support.derivePublicKey(extractSeedFromPkcs8(pkcs8));
                log.info("Sync signing key loaded from SYNC_SIGNING_KEY.");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialise the sync signing key", e);
        }
        this.keyId = HexFormat.of().formatHex(Ed25519Support.sha256(rawPublicKey)).substring(0, 16);
        log.info("Sync signing key ready: keyId={} publicKey={}", keyId, publicKeyB64Url());
    }

    /**
     * Reads the raw 32-byte public key from a PKCS#8 DER Ed25519 key's
     * optional {@code [1]} field:
     * {@code SEQUENCE { INTEGER, SEQUENCE(alg), OCTET STRING privateKey,
     * [1] BIT STRING publicKey }} — a minimal DER walk, no extra libraries.
     * Returns null when the field is absent (OpenSSL's encoding omits it).
     */
    static byte[] tryExtractPublicKeyFromPkcs8(byte[] pkcs8) {
        int[] pos = {0};
        expectTag(pkcs8, pos, 0x30); // outer SEQUENCE
        readLength(pkcs8, pos);
        expectTag(pkcs8, pos, 0x02); // version INTEGER
        skipValue(pkcs8, pos);
        expectTag(pkcs8, pos, 0x30); // algorithm SEQUENCE
        skipValue(pkcs8, pos);
        expectTag(pkcs8, pos, 0x04); // privateKey OCTET STRING
        skipValue(pkcs8, pos);
        if (pos[0] >= pkcs8.length || (pkcs8[pos[0]] & 0xFF) != 0xA1) {
            return null;
        }
        pos[0]++; // [1] public key
        int len = readLength(pkcs8, pos);
        int end = pos[0] + len;
        expectTag(pkcs8, pos, 0x03); // BIT STRING
        readLength(pkcs8, pos);
        if (pos[0] >= end || pkcs8[pos[0]++] != 0) { // unused-bits byte
            throw new IllegalArgumentException("Malformed public key BIT STRING in SYNC_SIGNING_KEY");
        }
        byte[] raw = new byte[32];
        if (pos[0] + 32 > end) {
            throw new IllegalArgumentException("Public key in SYNC_SIGNING_KEY is not 32 bytes");
        }
        System.arraycopy(pkcs8, pos[0], raw, 0, 32);
        return raw;
    }

    /** Extracts the 32-byte seed: the privateKey OCTET STRING wraps an inner OCTET STRING. */
    static byte[] extractSeedFromPkcs8(byte[] pkcs8) {
        int[] pos = {0};
        expectTag(pkcs8, pos, 0x30); // outer SEQUENCE
        readLength(pkcs8, pos);
        expectTag(pkcs8, pos, 0x02); // version INTEGER
        skipValue(pkcs8, pos);
        expectTag(pkcs8, pos, 0x30); // algorithm SEQUENCE
        skipValue(pkcs8, pos);
        expectTag(pkcs8, pos, 0x04); // privateKey OCTET STRING
        readLength(pkcs8, pos);
        expectTag(pkcs8, pos, 0x04); // inner OCTET STRING (the seed)
        int len = readLength(pkcs8, pos);
        if (len != 32) {
            throw new IllegalArgumentException("Ed25519 seed in SYNC_SIGNING_KEY is not 32 bytes");
        }
        byte[] seed = new byte[32];
        System.arraycopy(pkcs8, pos[0], seed, 0, 32);
        return seed;
    }

    private static void expectTag(byte[] der, int[] pos, int tag) {
        if (pos[0] >= der.length || (der[pos[0]++] & 0xFF) != tag) {
            throw new IllegalArgumentException("Unexpected DER structure in SYNC_SIGNING_KEY");
        }
    }

    private static int readLength(byte[] der, int[] pos) {
        int first = der[pos[0]++] & 0xFF;
        if (first < 0x80) {
            return first;
        }
        int count = first & 0x7F;
        int length = 0;
        for (int k = 0; k < count; k++) {
            length = (length << 8) | (der[pos[0]++] & 0xFF);
        }
        return length;
    }

    private static void skipValue(byte[] der, int[] pos) {
        int len = readLength(der, pos);
        pos[0] += len;
    }

    public String keyId() {
        return keyId;
    }

    public byte[] rawPublicKey() {
        return rawPublicKey.clone();
    }

    public String publicKeyB64Url() {
        return Ed25519Support.b64UrlEncode(rawPublicKey);
    }

    /** Signs data (already canonicalised by the caller) with the sync key. */
    public byte[] sign(byte[] data) {
        try {
            return Ed25519Support.sign(privateKey, data);
        } catch (Exception e) {
            throw new IllegalStateException("Sync ack signing failed", e);
        }
    }
}
