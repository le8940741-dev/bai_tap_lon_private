package com.auction.server.util;

/**
 * FILE ROLE: Unit tests for PasswordUtil — the password hashing and verification utility.
 *
 * WHAT IS TESTED:
 *   1. hash() + verify() round-trip with the same plaintext — proves the salt is
 *      correctly embedded in the stored string and recovered during verification.
 *   2. Wrong password returns false — proves the comparison is strict.
 *   3. Legacy hash (no salt prefix) — the admin seed row uses bare SHA-256("admin");
 *      verify() must handle this older format without crashing.
 *   4. Two hash() calls on the same plaintext produce different stored strings —
 *      proves that random salting is working (each call generates a new salt).
 *      Despite different stored strings, both must still verify correctly.
 *
 * WHY NO DATABASE NEEDED:
 *   PasswordUtil is a pure utility class with no dependencies on DAOs or services.
 *   All four tests run in-memory with no setup beyond the class import.
 *
 * IMPORT NOTES:
 *   org.junit.jupiter.api.Test       — marks a method as a JUnit 5 test case.
 *   org.junit.jupiter.api.Assertions — static assertion methods (assertTrue, assertFalse, etc.).
 */

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordUtilTest {

    /**
     * The fundamental contract: hash a password, then verify the same plaintext.
     * If this fails, the entire authentication system is broken.
     */
    @Test
    void hashAndVerify_roundTrip() {
        String hash = PasswordUtil.hash("secret123");
        // The stored hash contains the salt; verify() must extract it and recompute.
        assertTrue(PasswordUtil.verify("secret123", hash));
    }

    /**
     * A different password must not verify against a hash of the original.
     * If this passes when it should fail, attackers could log in with any password.
     */
    @Test
    void verify_wrongPassword_returnsFalse() {
        String hash = PasswordUtil.hash("secret123");
        assertFalse(PasswordUtil.verify("wrongpass", hash));
    }

    /**
     * The admin seed in DatabaseManager uses a bare SHA-256 hex string with no salt prefix
     * (no ":" separator).  This simulates that format: SHA-256("admin") as lowercase hex.
     *
     * PasswordUtil.verify() detects the absence of ":" and falls back to a
     * salt-less SHA-256 comparison — this must return true for password "admin".
     *
     * IMPORTANT: the hash 8c6976e5... is SHA-256 of the string "admin", not "admin123".
     * The README and admin login credentials both say password = "admin".
     */
    @Test
    void verify_legacyHex_matchesDirectSha256() {
        // SHA-256("admin") in lowercase hex — the exact string stored in the admin seed row.
        String legacyHash = "8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918";
        assertTrue(PasswordUtil.verify("admin", legacyHash));
    }

    /**
     * Two hash() calls on the same plaintext must produce different stored strings
     * because each call generates a fresh random 16-byte salt.
     *
     * This test verifies that salting is actually working:
     *   - Different stored strings → attacker can't use a precomputed rainbow table.
     *   - Both verify correctly → the salt extraction + recomputation is correct.
     */
    @Test
    void twoHashes_samePlaintext_produceDifferentSalts() {
        String h1 = PasswordUtil.hash("pw");
        String h2 = PasswordUtil.hash("pw");
        assertNotEquals(h1, h2,
                "Two hashes of the same password must differ (different random salts)");
        // Both must still verify correctly despite different stored values.
        assertTrue(PasswordUtil.verify("pw", h1));
        assertTrue(PasswordUtil.verify("pw", h2));
    }
}
