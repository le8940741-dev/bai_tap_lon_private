package com.auction.server.service;

import com.auction.server.dao.UserDAO;            // injected dependency — talks to the database
import com.auction.server.exception.AuthException; // thrown for any auth rule violation
import com.auction.server.factory.UserFactory;     // creates the right User subclass from role string
import com.auction.server.model.User;              // abstract domain model
import com.auction.server.model.UserRole;          // role enum for permission checks
import com.auction.server.util.PasswordUtil;       // hashes and verifies passwords

import java.util.List; // return type for getAllUsers()

/**
 * FILE ROLE: Business logic for all user-related operations.
 *
 * Handles registration, login, and admin ban.
 * This is a pure business-logic class — it knows nothing about TCP sockets,
 * JSON, or SQL.  It speaks in domain terms (User, AuthException) only.
 *
 * DEPENDENCY INJECTION:
 *   UserDAO is passed in via the constructor rather than created here.
 *   This makes UserService testable: tests inject a mock DAO (UserServiceTest
 *   does exactly this with Mockito) so no database is needed to test the logic.
 *
 * WHY NO STATIC METHODS:
 *   Static methods cannot be overridden or mocked.  Instance methods + constructor
 *   injection is the standard testable design for service classes.
 *
 * CALLED BY: ClientHandler (handles LOGIN, REGISTER, BAN_USER, GET_USERS messages)
 */
public final class UserService {

    // The data access object — only dependency; injected at construction time.
    private final UserDAO userDAO;

    public UserService(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    // ── Registration ──────────────────────────────────────────────────────────

    /**
     * Register a new user account.
     *
     * Validation rules (throws AuthException if violated):
     *   - Username must not be blank.
     *   - Password must be at least 4 characters.
     *   - Email must contain "@" (minimal check).
     *   - Role must be "BIDDER" or "SELLER" — self-registering as ADMIN is forbidden.
     *   - Username must not already be taken (checked via DAO).
     *
     * On success:
     *   - UserFactory creates the correct subclass (Bidder or Seller).
     *   - PasswordUtil.hash() salts and hashes the plaintext password.
     *   - The new User is persisted via userDAO.save().
     *
     * @return the newly created User with its database-assigned id
     */
    public User register(String username, String password, String email, String roleName) {
        // Input validation — throw early with a clear message.
        if (username == null || username.isBlank())
            throw new AuthException("Username must not be blank");
        if (password == null || password.length() < 4)
            throw new AuthException("Password must be at least 4 characters");
        if (email == null || !email.contains("@"))
            throw new AuthException("Invalid email address");

        // Parse the role string — throws IllegalArgumentException for unknown roles,
        // which the catch block converts to AuthException.
        UserRole role;
        try {
            role = UserRole.valueOf(roleName.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AuthException("Invalid role: " + roleName);
        }

        // Admins can only be created by seeding — never by self-registration.
        if (role == UserRole.ADMIN)
            throw new AuthException("Cannot self-register as ADMIN");

        // Duplicate username check — findByUsername() queries the DB.
        userDAO.findByUsername(username).ifPresent(u -> {
            throw new AuthException("Username already taken: " + username);
        });

        // Build the correct User subclass, hash the password, and persist.
        User user = UserFactory.create(role);
        user.setUsername(username);
        user.setPasswordHash(PasswordUtil.hash(password)); // salted SHA-256
        user.setEmail(email);
        return userDAO.save(user); // DAO sets user.id before returning
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    /**
     * Authenticate a user by username and password.
     *
     * Failure cases (all throw AuthException with the same "Invalid credentials"
     * message to avoid revealing whether the username exists):
     *   - Username not found in the database.
     *   - Account is banned (active = false).
     *   - Password doesn't match the stored hash.
     *
     * @return the authenticated User object (with id, role, etc.)
     */
    public User login(String username, String password) {
        // orElseThrow: if username not found, throw immediately.
        User user = userDAO.findByUsername(username)
                .orElseThrow(() -> new AuthException("Invalid credentials"));

        // Ban check before password verification — banned users get a different message.
        if (!user.isActive())
            throw new AuthException("Account is banned");

        // PasswordUtil.verify() recomputes the hash with the stored salt and compares.
        if (!PasswordUtil.verify(password, user.getPasswordHash()))
            throw new AuthException("Invalid credentials");

        return user;
    }

    // ── Admin operations ──────────────────────────────────────────────────────

    /**
     * Return all registered users.
     * No authorisation check here — ClientHandler.handleGetUsers() already
     * calls requireAdmin() before reaching this method.
     */
    public List<User> getAllUsers() {
        return userDAO.findAll();
    }

    /**
     * Ban (deactivate) a user account.
     *
     * @param targetId  the id of the user to ban
     * @param requester the admin performing the ban (must have ADMIN role)
     * @throws AuthException if requester is not an admin, or target not found
     */
    public void banUser(long targetId, User requester) {
        if (requester.getRole() != UserRole.ADMIN)
            throw new AuthException("Only admins can ban users");
        // Verify the target exists before trying to update.
        userDAO.findById(targetId)
                .orElseThrow(() -> new AuthException("User not found: " + targetId));
        userDAO.updateActive(targetId, false); // set active=0 in the database
    }
}
