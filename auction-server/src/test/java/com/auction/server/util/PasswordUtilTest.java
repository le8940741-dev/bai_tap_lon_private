package com.auction.server.util;

/**
 * Regression tests for {@link PasswordUtil} — documents both salted and legacy admin hashes.
 *
 * <p>Notice how assertions use JUnit 5’s {@link org.junit.jupiter.api.Assertions#assertTrue(boolean)}
 * to express security invariants students can extend when they change hashing algorithms.</p>
 */
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordUtilTest {

    @Test
    void hashAndVerify_roundTrip() {
        String hash = PasswordUtil.hash("secret123");
        // The stored hash contains the salt; verify() must extract it and recompute.
        assertTrue(PasswordUtil.verify("secret123", hash));
    }

    @Test
    void verify_wrongPassword_returnsFalse() {
        String hash = PasswordUtil.hash("secret123");
        assertFalse(PasswordUtil.verify("wrongpass", hash));
    }

    @Test
    void verify_legacyHex_matchesDirectSha256() {
        // SHA-256("admin") in lowercase hex - the exact string stored in the admin seed row.
        String legacyHash = "8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918";
        assertTrue(PasswordUtil.verify("admin", legacyHash));
    }

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
