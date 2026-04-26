package com.auction.server.model;

/**
 * FILE ROLE: Concrete user type representing a buyer / auction participant.
 *
 * Bidders can:
 *   - Browse all auctions (anyone can)
 *   - Place manual bids (canBid() = true)
 *   - Register auto-bids (canBid() = true)
 *
 * Bidders cannot:
 *   - Create items or auctions (canSell() = false)
 *   - Access admin functions (getRole() = BIDDER, not ADMIN)
 *
 * The UserFactory creates a Bidder when the registered role is "BIDDER".
 * SQLiteUserDAO recreates a Bidder when reading a row with role='BIDDER'.
 */
public final class Bidder extends User {
    public Bidder() { super(); }

    /** Always BIDDER — used for role checks in services and ClientHandler. */
    @Override public UserRole getRole() { return UserRole.BIDDER; }

    /** True — Bidders are the only users allowed to place bids. */
    @Override public boolean canBid()  { return true; }

    /** False — Bidders cannot list items for sale. */
    @Override public boolean canSell() { return false; }
}
