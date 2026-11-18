package com.rescuemesh.server.sync;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Ed25519 helpers on the JDK built-in provider (JEP 339), following the
 * Prompt 4 identity decisions: a raw 32-byte public key is turned into an
 * SPKI key by prepending the fixed 12-byte RFC 8410 prefix
 * {@code 30 2A 30 05 06 03 2B 65 70 03 21 00}; {@code originDeviceId} is the
 * lowercase hex SHA-256 of the raw key bytes; keys and signatures travel as
 * Base64url without padding.
 */
public final class Ed25519Support {

    /** RFC 8410 SPKI prefix for Ed25519 (algorithm identifier + BIT STRING header). */
    private static final byte[] SPKI_PREFIX = {
            0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    private Ed25519Support() {
    }

    public static PublicKey publicKeyFromRaw(byte[] rawPublicKey) throws Exception {
        if (rawPublicKey == null || rawPublicKey.length != 32) {
            throw new IllegalArgumentException("Ed25519 raw public key must be 32 bytes");
        }
        byte[] spki = new byte[SPKI_PREFIX.length + rawPublicKey.length];
        System.arraycopy(SPKI_PREFIX, 0, spki, 0, SPKI_PREFIX.length);
        System.arraycopy(rawPublicKey, 0, spki, SPKI_PREFIX.length, rawPublicKey.length);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
    }

    // -- Ed25519 public-key derivation (RFC 8032 §5.1.5) --------------------
    // The JDK cannot derive an Ed25519 public key from a private key, and
    // OpenSSL's PKCS#8 output omits the optional public-key field, so the
    // sync-key loader derives it from the 32-byte seed: SHA-512 the seed,
    // clamp the first half, scalar-multiply the base point. BigInteger
    // twisted-Edwards arithmetic — startup-only, correctness over speed.
    // Verified against the §3 test vector (seed 00..1f -> the vector pubkey).

    private static final java.math.BigInteger FIELD_Q =
            java.math.BigInteger.TWO.pow(255).subtract(java.math.BigInteger.valueOf(19));
    private static final java.math.BigInteger CURVE_D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(FIELD_Q)).mod(FIELD_Q);
    private static final java.math.BigInteger SQRT_M1 =
            java.math.BigInteger.TWO.modPow(FIELD_Q.subtract(java.math.BigInteger.ONE).divide(BigInteger.valueOf(4)), FIELD_Q);

    private static java.math.BigInteger xRecover(java.math.BigInteger y) {
        java.math.BigInteger yy = y.multiply(y).mod(FIELD_Q);
        java.math.BigInteger xx = yy.subtract(java.math.BigInteger.ONE)
                .multiply(CURVE_D.multiply(yy).add(java.math.BigInteger.ONE).modInverse(FIELD_Q))
                .mod(FIELD_Q);
        java.math.BigInteger x = xx.modPow(FIELD_Q.add(java.math.BigInteger.valueOf(3))
                .divide(BigInteger.valueOf(8)), FIELD_Q);
        if (!x.multiply(x).subtract(xx).mod(FIELD_Q).equals(java.math.BigInteger.ZERO)) {
            x = x.multiply(SQRT_M1).mod(FIELD_Q);
        }
        if (x.testBit(0)) {
            x = FIELD_Q.subtract(x);
        }
        return x;
    }

    /** Twisted-Edwards point addition (a = -1); points are {x, y} mod q. */
    private static java.math.BigInteger[] edwardsAdd(java.math.BigInteger[] p, java.math.BigInteger[] r) {
        java.math.BigInteger x1y2 = p[0].multiply(r[1]);
        java.math.BigInteger x2y1 = r[0].multiply(p[1]);
        java.math.BigInteger dxxyy = CURVE_D.multiply(p[0]).multiply(r[0]).multiply(p[1]).multiply(r[1]).mod(FIELD_Q);
        java.math.BigInteger x3 = x1y2.add(x2y1)
                .multiply(java.math.BigInteger.ONE.add(dxxyy).modInverse(FIELD_Q)).mod(FIELD_Q);
        java.math.BigInteger y3 = p[1].multiply(r[1]).add(p[0].multiply(r[0]))
                .multiply(java.math.BigInteger.ONE.subtract(dxxyy).modInverse(FIELD_Q)).mod(FIELD_Q);
        return new java.math.BigInteger[]{x3, y3};
    }

    /** Derives the raw 32-byte Ed25519 public key from a 32-byte seed. */
    public static byte[] derivePublicKey(byte[] seed) {
        if (seed == null || seed.length != 32) {
            throw new IllegalArgumentException("Ed25519 seed must be 32 bytes");
        }
        byte[] h;
        try {
            h = MessageDigest.getInstance("SHA-512").digest(seed);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
        byte[] aBytes = new byte[32];
        System.arraycopy(h, 0, aBytes, 0, 32);
        aBytes[0] &= (byte) 248;
        aBytes[31] &= 127;
        aBytes[31] |= 64;
        java.math.BigInteger a = new java.math.BigInteger(1, reverse(aBytes.clone()));

        java.math.BigInteger by = BigInteger.valueOf(4)
                .multiply(BigInteger.valueOf(5).modInverse(FIELD_Q)).mod(FIELD_Q);
        java.math.BigInteger[] base = {xRecover(by), by};
        java.math.BigInteger[] point = {java.math.BigInteger.ZERO, java.math.BigInteger.ONE};
        for (int bit = 0; bit < 256; bit++) {
            if (a.testBit(bit)) {
                point = edwardsAdd(point, base);
            }
            base = edwardsAdd(base, base);
        }
        byte[] yBytes = reverse(point[1].toByteArray());
        byte[] raw = new byte[32];
        System.arraycopy(yBytes, 0, raw, 0, Math.min(32, yBytes.length));
        if (point[0].testBit(0)) {
            raw[31] |= (byte) 0x80;
        }
        return raw;
    }

    private static byte[] reverse(byte[] in) {
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = in[in.length - 1 - i];
        }
        return out;
    }

    /** Raw 32 bytes from an Ed25519 public key's SPKI encoding. */
    public static byte[] rawFromPublicKey(PublicKey publicKey) {
        byte[] spki = publicKey.getEncoded();
        byte[] raw = new byte[32];
        System.arraycopy(spki, spki.length - 32, raw, 0, 32);
        return raw;
    }

    /** @return true only if {@code signature} verifies; false on any error or mismatch. */
    public static boolean verify(byte[] rawPublicKey, byte[] signature, byte[] data) {
        try {
            if (signature == null || signature.length != 64) {
                return false;
            }
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKeyFromRaw(rawPublicKey));
            verifier.update(data);
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    public static byte[] sign(PrivateKey privateKey, byte[] data) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(data);
        return signer.sign();
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** originDeviceId for a raw public key: lowercase hex SHA-256 of the raw bytes. */
    public static String deviceIdFor(byte[] rawPublicKey) {
        return HexFormat.of().formatHex(sha256(rawPublicKey));
    }

    public static String b64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Decodes Base64url (padding optional). Throws IllegalArgumentException on bad input. */
    public static byte[] b64UrlDecode(String text) {
        return Base64.getUrlDecoder().decode(text);
    }
}
