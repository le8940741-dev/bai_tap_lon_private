package com.auction.server.model;

/**
 * Strongly typed role names stored in the {@code users.role} column and mirrored in {@link com.auction.common.dto.UserDTO}.
 *
 * <p>Keeping permissions as enum constants avoids magic strings scattered through {@code if} statements.</p>
 */
public enum UserRole {
    BIDDER,  // can place bids; cannot list items
    SELLER,  // can list items and open auctions; cannot bid
    ADMIN    // can view all users and ban them; cannot bid or sell
}
