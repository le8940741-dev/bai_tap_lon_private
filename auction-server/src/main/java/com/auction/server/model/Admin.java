package com.auction.server.model;

/**
 * Concrete {@link User} role with moderation powers only.
 *
 * <p>Separating admin into its own subclass keeps permission logic localized — compare with
 * scattering {@code if (role == ADMIN)} checks across every service method.</p>
 */
public final class Admin extends User {
    public Admin() { super(); }

    @Override public UserRole getRole() { return UserRole.ADMIN; }

    @Override public boolean canBid()  { return false; }

    @Override public boolean canSell() { return false; }
}
