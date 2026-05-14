package com.auction.server.service;

/**
 * {@link org.junit.jupiter.api.Test}-driven examples of {@link UserService} with Mockito stubs.
 *
 * <p><b>Why mock {@link com.auction.server.dao.UserDAO}?</b> Registration and login logic can be
 * validated without spinning up SQLite — {@code when(...).thenReturn(...)} simulates duplicate
 * username checks, empty results, etc.</p>
 *
 * <p><b>Pattern:</b> Arrange-Act-Assert blocks in each test method; {@link org.junit.jupiter.api.BeforeEach}
 * rebuilds fresh mocks so tests stay independent.</p>
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

    // The mock DAO - no real database; returns what we configure in each test.
    UserDAO mockUserDAO;

    // The class under test - the real UserService, not a mock.
    UserService service;

    @BeforeEach
    void setup() {
        mockUserDAO = Mockito.mock(UserDAO.class);
        service = new UserService(mockUserDAO);

        // By default: no user with any username exists -> username is available.
        when(mockUserDAO.findByUsername(anyString())).thenReturn(Optional.empty());

        // By default: save() assigns id=1 to the user and returns it.
        when(mockUserDAO.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0); // the User object passed to save()
            u.setId(1L);
            return u;
        });
    }

    // Registration tests

    @Test
    void register_validBidder_succeeds() {
        User u = service.register("alice", "pass1", "alice@x.com", "BIDDER");
        assertEquals("alice", u.getUsername());
        assertEquals(UserRole.BIDDER, u.getRole());
        verify(mockUserDAO).save(any()); // assert save() was actually called once
    }

    @Test
    void register_blankUsername_throws() {
        assertThrows(AuthException.class,
                () -> service.register("  ", "pass1", "a@x.com", "BIDDER"));
    }

    @Test
    void register_shortPassword_throws() {
        assertThrows(AuthException.class,
                () -> service.register("alice", "ab", "a@x.com", "BIDDER"));
    }

    @Test
    void register_adminRole_throws() {
        assertThrows(AuthException.class,
                () -> service.register("hacker", "pass1", "h@x.com", "ADMIN"));
    }

    @Test
    void register_duplicateUsername_throws() {
        // Override the default stub: "alice" is now already registered.
        Bidder existing = new Bidder();
        existing.setUsername("alice");
        when(mockUserDAO.findByUsername("alice")).thenReturn(Optional.of(existing));

        assertThrows(AuthException.class,
                () -> service.register("alice", "pass1", "new@x.com", "BIDDER"));

        // save() must NOT have been called - we shouldn't persist a duplicate.
        verify(mockUserDAO, never()).save(any());
    }

    // Login tests

    @Test
    void login_wrongPassword_throws() {
        Bidder stored = new Bidder();
        stored.setUsername("alice");
        stored.setPasswordHash("wrong_hash_value"); // won't match any password
        stored.setActive(true);
        when(mockUserDAO.findByUsername("alice")).thenReturn(Optional.of(stored));

        assertThrows(AuthException.class, () -> service.login("alice", "correctpass"));
    }

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

    @Test
    void login_unknownUser_throws() {
        // mockUserDAO already returns Optional.empty() for any username by default.
        AuthException ex = assertThrows(AuthException.class,
                () -> service.login("ghost", "pass"));
        assertEquals("Invalid credentials", ex.getMessage());
    }
}
