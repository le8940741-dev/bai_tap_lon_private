package com.auction.server.model;

/**
 * FILE ROLE: Concrete user type representing an item seller.
 *
 * Sellers can:
 *   - Create items (CREATE_ITEM request)
 *   - Open auctions for their items (CREATE_AUCTION request)
 *   - Cancel their own open/running auctions (CANCEL_AUCTION)
 *   - Browse all auctions (anyone can)
 *
 * Sellers cannot:
 *   - Place bids (canBid() = false — they cannot buy their own items)
 *   - Access admin functions
 *
 * The SellerDashboardController is shown exclusively to Seller accounts after login.
 */
public final class Seller extends User {
    public Seller() { super(); }

    @Override public UserRole getRole() { return UserRole.SELLER; }

    /** False — Sellers are prohibited from bidding to prevent self-dealing. */
    @Override public boolean canBid()  { return false; }

    /** True — Sellers are the only users allowed to list items. */
    @Override public boolean canSell() { return true; }
}
