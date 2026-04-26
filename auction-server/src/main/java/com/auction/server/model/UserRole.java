package com.auction.server.model;

/**
 * FILE ROLE: Enumeration of the three user roles in the system.
 *
 * Stored as TEXT in SQLite (not as an integer code) so the database is
 * human-readable without a lookup table.
 *
 * Used in:
 *   - User.getRole() — every User subclass returns one of these.
 *   - SQLiteUserDAO.map() — converts the DB TEXT back to this enum.
 *   - UserFactory.create(roleName) — parses the string from RegisterRequest.
 *   - ClientHandler.requireAdmin() — checks if currentUser.getRole() == ADMIN.
 */
public enum UserRole {
    BIDDER,  // can place bids; cannot list items
    SELLER,  // can list items and open auctions; cannot bid
    ADMIN    // can view all users and ban them; cannot bid or sell
}
