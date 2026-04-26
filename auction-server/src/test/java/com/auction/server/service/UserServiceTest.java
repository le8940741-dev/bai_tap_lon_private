package com.auction.server.service;

/**
 * FILE ROLE: Unit tests for UserService — registration, login, and ban logic.
 *
 * TESTING STRATEGY — MOCK DAO:
 *   UserService has one dependency: UserDAO.
 *   We use Mockito to create a mock UserDAO instead of a real SQLite connection.
 *   This means:
 *     - Tests run in milliseconds (no disk I/O).
 *     - Tests are deterministic (no shared state between test methods).
 *     - We can simulate edge cases (e.g. "username already taken") by configuring
 *       the mock's return value without inserting real database rows.
 *
 * HOW MOCKITO WORKS HERE:
 *   Mockito.mock(UserDAO.class) creates a proxy object that implements UserDAO.
 *   By default, all methods return "empty" values (null, 0, Optional.empty()).
 *   when(...).thenReturn(...) overrides the return value for specific inputs.
 *   verify(...) asserts that a method was called (used to confirm save() was invoked).
 *
 * IMPORT NOTES:
 *   org.junit.jupiter.api.BeforeEach   — runs setup() before each @Test method.
 *   org.junit.jupiter.api.Test         — marks a test method.
 *   org.mockito.Mockito                — creates mock objects and stubs.
 *   org.mockito.Mockito.when           — configures stub return values.
 *   org.mockito.ArgumentMatchers.any   — matches any argument of the given type.
 *   org.mockito.ArgumentMatchers.anyString — matches any String argument.
 *   org.mockito.Mockito.verify         — asserts a method was called.
 *   static org.junit.jupiter.api.Assertions.* — assertion methods.
 */

import com.auction.server.exception.AuthException;
import com.auction.server.model.User;
import com.auction.server.model.UserRole;
import com.auction.server.model.Bidder;
import com.auction.server.dao.UserDAO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserServiceTest {

    // The mock DAO — no real database; returns what we configure in each test.
    UserDAO mockUserDAO;

    // The class under test — the real UserService, not a mock.
    UserService service;

    /**
     * Run before each @Test.
     * Creates a fresh mock DAO and a fresh UserService so tests don't share state.
     *
     * Default stubs:
     *   findByUsername(anything) → Optional.empty()   (username is available by default)
     *   save(anything) → sets id=1 and returns the user  (simulates DB auto-increment)
     */
    @BeforeEach
    void setup() {
        mockUserDAO = Mockito.mock(UserDAO.class);
        service = new UserService(mockUserDAO);

        // By default: no user with any username exists → username is available.
        when(mockUserDAO.findByUsername(anyString())).thenReturn(Optional.empty());

        // By default: save() assigns id=1 to the user and returns it.
        when(mockUserDAO.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0); // the User object passed to save()
            u.setId(1L);
            return u;
        });
    }

    // ── Registration tests ─────────────────────────────────────────────────────

    /**
     * Happy path: a valid BIDDER registration should call save() and
     * return a User with the correct username and role.
     */
    @Test
    void register_validBidder_succeeds() {
        User u = service.register("alice", "pass1", "alice@x.com", "BIDDER");
        assertEquals("alice", u.getUsername());
        assertEquals(UserRole.BIDDER, u.getRole());
        verify(mockUserDAO).save(any()); // assert save() was actually called once
    }

    /**
     * Blank username (whitespace only) must throw AuthException before any DAO call.
     * Verifies client-side validation in UserService — the DAO should never be reached.
     */
    @Test
    void register_blankUsername_throws() {
        assertThrows(AuthException.class,
                () -> service.register("  ", "pass1", "a@x.com", "BIDDER"));
    }

    /**
     * Passwords shorter than 4 characters are rejected.
     * Verifies the minimum-length rule in UserService.
     */
    @Test
    void register_shortPassword_throws() {
        assertThrows(AuthException.class,
                () -> service.register("alice", "ab", "a@x.com", "BIDDER"));
    }

    /**
     * Self-registering as ADMIN must be rejected regardless of other valid inputs.
     * Only the DB seed process can create admin accounts.
     */
    @Test
    void register_adminRole_throws() {
        assertThrows(AuthException.class,
                () -> service.register("hacker", "pass1", "h@x.com", "ADMIN"));
    }

    /**
     * If the username is already taken (mock returns an existing user),
     * register() must throw AuthException without calling save().
     */
    @Test
    void register_duplicateUsername_throws() {
        // Override the default stub: "alice" is now already registered.
        Bidder existing = new Bidder();
        existing.setUsername("alice");
        when(mockUserDAO.findByUsername("alice")).thenReturn(Optional.of(existing));

        assertThrows(AuthException.class,
                () -> service.register("alice", "pass1", "new@x.com", "BIDDER"));

        // save() must NOT have been called — we shouldn't persist a duplicate.
        verify(mockUserDAO, never()).save(any());
    }

    // ── Login tests ────────────────────────────────────────────────────────────

    /**
     * A wrong password should fail even if the username exists.
     * PasswordUtil.verify() will return false for the mismatched hash.
     * Note: "wrong_hash_value" has no ":" separator, so verify() uses the
     * legacy path (SHA-256 comparison) which also won't match "correctpass".
     */
    @Test
    void login_wrongPassword_throws() {
        Bidder stored = new Bidder();
        stored.setUsername("alice");
        stored.setPasswordHash("wrong_hash_value"); // won't match any password
        stored.setActive(true);
        when(mockUserDAO.findByUsername("alice")).thenReturn(Optional.of(stored));

        assertThrows(AuthException.class, () -> service.login("alice", "correctpass"));
    }

    /**
     * A banned user (active=false) should be rejected before password verification.
     * The error message must be "Account is banned" — not "Invalid credentials" —
     * so the user knows why they can't log in.
     */
    @Test
    void login_bannedUser_throws() {
        Bidder stored = new Bidder();
        stored.setUsername("banned");
        stored.setPasswordHash("hash");
        stored.setActive(false); // banned!
        when(mockUserDAO.findByUsername("banned")).thenReturn(Optional.of(stored));

        AuthException ex = assertThrows(AuthException.class,
                () -> service.login("banned", "pass"));
        assertEquals("Account is banned", ex.getMessage());
    }

    /**
     * A username that doesn't exist should throw AuthException with "Invalid credentials".
     * We deliberately use the same message as wrong-password to avoid revealing
     * whether the username exists (prevents username enumeration attacks).
     */
    @Test
    void login_unknownUser_throws() {
        // mockUserDAO already returns Optional.empty() for any username by default.
        AuthException ex = assertThrows(AuthException.class,
                () -> service.login("ghost", "pass"));
        assertEquals("Invalid credentials", ex.getMessage());
    }
}
