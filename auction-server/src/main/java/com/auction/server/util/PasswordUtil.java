package com.auction.server.util;

import java.security.MessageDigest; // Java's built-in hash algorithm provider
import java.security.NoSuchAlgorithmException; // thrown if SHA-256 is unavailable (never in practice)
import java.security.SecureRandom; // cryptographically secure random number generator for salts
import java.util.Base64;           // encodes/decodes byte arrays to/from Base64 strings for storage

/**
 * Cryptographic helpers for storing passwords as irreversible hashes plus salt.
 *
 * <p><b>Why not store plaintext?</b> Anyone with database access could steal accounts. Hashing
 * means the server compares derived bytes, not memorizable passwords. {@link java.security.SecureRandom}
 * supplies unpredictable salts so two users with the same password still get different stored strings.</p>
 *
 * <p><b>Backward compatibility:</b> {@link #verify} detects the seeded admin row that predates salting.</p>
 */
public final class PasswordUtil {

    // SecureRandom is thread-safe and cryptographically strong - safe to share.
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtil() {} // utility class - no instances

    public static String hash(String plainText) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt); // fill with cryptographically random bytes
        return Base64.getEncoder().encodeToString(salt) + ":" + sha256Hex(salt, plainText);
    }

    public static boolean verify(String plainText, String stored) {
        if (stored == null) return false;

        // Legacy format: no salt prefix, just a raw hex hash (admin seed uses this).
        if (!stored.contains(":")) {
            // Re-compute SHA-256 of the password with an empty salt and compare.
            return sha256Hex(new byte[0], plainText).equalsIgnoreCase(stored);
        }

        // Modern format: split on ":" to recover the salt, then re-hash and compare.
        String[] parts = stored.split(":", 2);
        byte[] salt = Base64.getDecoder().decode(parts[0]); // decode Base64 -> bytes
        String expectedHash = parts[1];
        return expectedHash.equalsIgnoreCase(sha256Hex(salt, plainText));
    }

    private static String sha256Hex(byte[] salt, String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);                    // feed the salt first
            byte[] digest = md.digest(text.getBytes()); // then append the password and hash
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b)); // convert each byte to 2-char hex
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every Java SE implementation - this can never happen.
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
