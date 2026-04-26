package com.auction.server.util;

import java.security.MessageDigest; // Java's built-in hash algorithm provider
import java.security.NoSuchAlgorithmException; // thrown if SHA-256 is unavailable (never in practice)
import java.security.SecureRandom; // cryptographically secure random number generator for salts
import java.util.Base64;           // encodes/decodes byte arrays to/from Base64 strings for storage

/**
 * FILE ROLE: Stateless password hashing and verification utility.
 *
 * ALGORITHM: SHA-256 with a random 16-byte salt.
 *
 * STORED FORMAT in the database:
 *   BASE64(salt) + ":" + HEX(SHA-256(salt || password))
 *   Example: "Zt3kQW...=:8a4f2c1e..."
 *
 * WHY SALT:
 *   Without a salt, two users with the same password produce the same hash.
 *   An attacker who steals the database can use a precomputed "rainbow table"
 *   (hash → password mapping) to reverse millions of hashes at once.
 *   A random salt per-user makes every hash unique even for identical passwords,
 *   so the attacker must brute-force each hash separately.
 *
 * WHY NOT BCRYPT/ARGON2:
 *   Those are stronger (deliberately slow), but require external dependencies.
 *   SHA-256 + salt keeps the dependency footprint zero (built into Java).
 *   For a production system, replace with BCrypt from spring-security-crypto.
 *
 * LEGACY HASH SUPPORT:
 *   The admin seed in DatabaseManager uses a bare SHA-256 hex with no salt prefix
 *   (no ":" separator).  verify() detects this and falls back to a direct comparison.
 *
 * USED BY:
 *   - UserService.register() — hashes the new password before storing.
 *   - UserService.login()    — verifies the submitted password against the stored hash.
 */
public final class PasswordUtil {

    // SecureRandom is thread-safe and cryptographically strong — safe to share.
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtil() {} // utility class — no instances

    /**
     * Hash a plaintext password.
     *
     * Steps:
     *   1. Generate 16 random bytes (the salt).
     *   2. Encode the salt to Base64 for storage.
     *   3. Compute SHA-256(salt bytes || password UTF-8 bytes).
     *   4. Return "BASE64(salt):HEX(hash)".
     *
     * @param plainText the user's plaintext password
     * @return the stored hash string in "salt:hash" format
     */
    public static String hash(String plainText) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt); // fill with cryptographically random bytes
        return Base64.getEncoder().encodeToString(salt) + ":" + sha256Hex(salt, plainText);
    }

    /**
     * Verify a plaintext password against a stored hash.
     *
     * Handles two stored formats:
     *   1. "BASE64(salt):HEX(hash)" — modern format produced by hash()
     *   2. "HEX(SHA256(password))"  — legacy format used by the admin seed row
     *
     * @param plainText the password the user submitted at login
     * @param stored    the value retrieved from the database password_hash column
     * @return true if the password matches, false otherwise
     */
    public static boolean verify(String plainText, String stored) {
        if (stored == null) return false;

        // Legacy format: no salt prefix, just a raw hex hash (admin seed uses this).
        if (!stored.contains(":")) {
            // Re-compute SHA-256 of the password with an empty salt and compare.
            return sha256Hex(new byte[0], plainText).equalsIgnoreCase(stored);
        }

        // Modern format: split on ":" to recover the salt, then re-hash and compare.
        String[] parts = stored.split(":", 2);
        byte[] salt = Base64.getDecoder().decode(parts[0]); // decode Base64 → bytes
        String expectedHash = parts[1];
        return expectedHash.equalsIgnoreCase(sha256Hex(salt, plainText));
    }

    /**
     * Internal helper: compute SHA-256(salt || password) and return as lowercase hex.
     *
     * @param salt      the random salt bytes (may be empty for legacy hashes)
     * @param text      the plaintext password
     * @return lowercase hex string of the SHA-256 digest
     */
    private static String sha256Hex(byte[] salt, String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);                    // feed the salt first
            byte[] digest = md.digest(text.getBytes()); // then append the password and hash
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b)); // convert each byte to 2-char hex
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every Java SE implementation — this can never happen.
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
