package com.rescuemesh.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Generation and hashing for the one-time codes of auth v2 (email
 * verification codes and role access codes).
 *
 * <p>Storage rule: only {@link #sha256Hex(String)} of a code is ever
 * persisted; the plaintext travels exclusively through {@code MailService}
 * (and, for role codes, the one-time admin response — see
 * {@code RoleAccessCode}).
 */
public final class AccessCodes {

    /**
     * Role-code alphabet with the look-alike characters removed (no 0/O,
     * no 1/I/L), so a code read off an email or read out loud cannot be
     * mistyped.
     */
    private static final String ROLE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    private AccessCodes() {
    }

    /** 6-digit numeric email verification code, zero-padded ("042317"). */
    public static String generateVerificationCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /** Human-typeable role access code: {@code RM-XXXXX-XXXXX}. */
    public static String generateRoleAccessCode() {
        return "RM-" + group() + "-" + group();
    }

    private static String group() {
        StringBuilder sb = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            sb.append(ROLE_ALPHABET.charAt(RANDOM.nextInt(ROLE_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Lowercase hex SHA-256 of the plaintext code — the only form ever stored. */
    public static String sha256Hex(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed by the JCA spec", e);
        }
    }
}
