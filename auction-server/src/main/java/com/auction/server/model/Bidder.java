package com.auction.server.model;

/**
 * Concrete {@link User} role used for buyers.
 *
 * <p>Template method / polymorphism: overrides {@link #canBid()} / {@link #canSell()} so
 * {@link com.auction.server.service.BidService} can call the abstract API without {@code instanceof} chains.</p>
 */
public final class Bidder extends User {
    public Bidder() { super(); }

    @Override public UserRole getRole() { return UserRole.BIDDER; }

    @Override public boolean canBid()  { return true; }

    @Override public boolean canSell() { return false; }
}
