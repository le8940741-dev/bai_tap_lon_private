package com.auction.server.dao;

import com.auction.server.model.User;

import java.util.List;     // ordered collection of users for findAll()
import java.util.Optional; // null-safe container for single-result queries

/**
 * FILE ROLE: Data Access Object interface for User persistence.
 *
 * PATTERN: DAO (Data Access Object)
 *   The DAO pattern separates persistence logic from business logic.
 *   Services (UserService) depend on this interface, not on SQLiteUserDAO directly.
 *   This means:
 *     1. Tests can inject a mock DAO without touching the database.
 *     2. You could swap SQLite for PostgreSQL by writing a new implementation —
 *        UserService code stays identical.
 *
 * WHY Optional:
 *   findById() and findByUsername() might find nothing (user doesn't exist).
 *   Returning Optional forces callers to handle the "not found" case explicitly
 *   rather than remembering to null-check.
 *
 * IMPLEMENTED BY: SQLiteUserDAO
 * USED BY: UserService
 */
public interface UserDAO {

    /**
     * Persist a new User to the database.
     * The DAO sets user.id from the generated AUTOINCREMENT key before returning.
     *
     * @param user a populated User object with no id yet (id == 0)
     * @return the same User object with id now set
     */
    User save(User user);

    /**
     * Find a user by their database primary key.
     * Returns Optional.empty() if no user with that id exists.
     * Used by UserService.banUser() to verify the target user exists.
     */
    Optional<User> findById(long id);

    /**
     * Find a user by their login name.
     * Returns Optional.empty() if the username is not taken.
     * Used by:
     *   - UserService.login()    — to retrieve the user for password verification.
     *   - UserService.register() — to check that the username isn't already taken.
     */
    Optional<User> findByUsername(String username);

    /**
     * Return all registered users.
     * Used by UserService.getAllUsers(), which is called for the admin panel.
     */
    List<User> findAll();

    /**
     * Set the active flag on a user account.
     * active=false means the user is banned and cannot log in.
     * Called by UserService.banUser() after admin authorization is confirmed.
     *
     * @param userId the id of the user to ban/unban
     * @param active false to ban, true to restore
     */
    void updateActive(long userId, boolean active);
}
