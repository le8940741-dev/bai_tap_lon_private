package com.auction.server.model;

/**
 * FILE ROLE: Concrete user type with administrative privileges.
 *
 * Admins can:
 *   - View the full user list (GET_USERS)
 *   - Ban any non-admin user (BAN_USER)
 *   - Cancel any auction regardless of owner (CANCEL_AUCTION)
 *   - Browse all auctions (anyone can)
 *
 * Admins cannot:
 *   - Place bids (canBid() = false)
 *   - Create items or auctions (canSell() = false)
 *
 * The default admin account is seeded by DatabaseManager.initSchema()
 * with username="admin", password="admin".
 * The AdminController is shown exclusively to Admin accounts after login.
 */
public final class Admin extends User {
    public Admin() { super(); }

    @Override public UserRole getRole() { return UserRole.ADMIN; }

    /** False — Admins are system managers, not bidders. */
    @Override public boolean canBid()  { return false; }

    /** False — Admins manage the system, not items. */
    @Override public boolean canSell() { return false; }
}
