package com.auction.server.model;

/**
 * Concrete {@link User} role representing vendors who list inventory.
 *
 * <p>{@link com.auction.server.service.ItemService} and {@link com.auction.server.service.AuctionService}
 * check {@link #canSell()} before mutating catalog tables.</p>
 */
public final class Seller extends User {
    public Seller() { super(); }

    @Override public UserRole getRole() { return UserRole.SELLER; }

    @Override public boolean canBid()  { return false; }

    @Override public boolean canSell() { return true; }
}
