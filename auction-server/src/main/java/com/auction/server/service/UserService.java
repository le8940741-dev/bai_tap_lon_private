package com.auction.server.service;

import com.auction.server.dao.UserDAO;            // injected dependency - talks to the database
import com.auction.server.exception.AuthException; // thrown for any auth rule violation
import com.auction.server.factory.UserFactory;     // creates the right User subclass from role string
import com.auction.server.model.User;              // abstract domain model
import com.auction.server.model.UserRole;          // role enum for permission checks
import com.auction.server.util.PasswordUtil;       // hashes and verifies passwords

import java.util.List; // return type for getAllUsers()

/**
 * Authentication-centric service: register, login, list users, ban accounts.
 *
 * <p><b>Dependency inversion:</b> Depends on the {@link com.auction.server.dao.UserDAO} <i>interface</i>,
 * not on SQLite, so tests can substitute a mock with the Mockito library ({@code org.mockito.Mockito}).</p>
 *
 * <p><b>Factory tie-in:</b> Registration delegates actual object construction to
 * {@link com.auction.server.factory.UserFactory} so the correct {@link com.auction.server.model.User}
 * subclass is created from a string role.</p>
 */
public final class UserService {

    // The data access object - only dependency; injected at construction time.
    private final UserDAO userDAO;

    public UserService(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    // Registration

    public User register(String username, String password, String email, String roleName) {
        // Input validation - throw early with a clear message.
        if (username == null || username.isBlank())
            throw new AuthException("Username must not be blank");
        if (password == null || password.length() < 4)
            throw new AuthException("Password must be at least 4 characters");
        if (email == null || !email.contains("@"))
            throw new AuthException("Invalid email address");

        // Parse the role string - throws IllegalArgumentException for unknown roles,
        // which the catch block converts to AuthException.
        UserRole role;
        try {
            role = UserRole.valueOf(roleName.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AuthException("Invalid role: " + roleName);
        }

        // Admins can only be created by seeding - never by self-registration.
        if (role == UserRole.ADMIN)
            throw new AuthException("Cannot self-register as ADMIN");

        // Duplicate username check - findByUsername() queries the DB.
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

    // Login

    public User login(String username, String password) {
        // orElseThrow: if username not found, throw immediately.
        User user = userDAO.findByUsername(username)
                .orElseThrow(() -> new AuthException("Invalid credentials"));

        // Ban check before password verification - banned users get a different message.
        if (!user.isActive())
            throw new AuthException("Account is banned");

        // PasswordUtil.verify() recomputes the hash with the stored salt and compares.
        if (!PasswordUtil.verify(password, user.getPasswordHash()))
            throw new AuthException("Invalid credentials");

        return user;
    }

    // Admin operations

    public List<User> getAllUsers() {
        return userDAO.findAll();
    }

    public void banUser(long targetId, User requester) {
        if (requester.getRole() != UserRole.ADMIN)
            throw new AuthException("Only admins can ban users");
        // Verify the target exists before trying to update.
        userDAO.findById(targetId)
                .orElseThrow(() -> new AuthException("User not found: " + targetId));
        userDAO.updateActive(targetId, false); // set active=0 in the database
    }
}
