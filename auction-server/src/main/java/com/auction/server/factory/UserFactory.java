package com.auction.server.factory;

import com.auction.server.model.Admin;    // concrete Admin subclass
import com.auction.server.model.Bidder;  // concrete Bidder subclass
import com.auction.server.model.Seller;  // concrete Seller subclass
import com.auction.server.model.User;    // abstract base returned by the factory
import com.auction.server.model.UserRole; // enum selecting which subclass to create

/**
 * Factory Method pattern for {@link com.auction.server.model.User} subclasses.
 *
 * <p>Callers work with the abstract {@link com.auction.server.model.User} type; this class picks
 * {@link com.auction.server.model.Bidder}, {@link com.auction.server.model.Seller}, or {@link com.auction.server.model.Admin}.
 * The {@code switch} on {@link com.auction.server.model.UserRole} must stay exhaustive whenever the enum grows.</p>
 */
public final class UserFactory {

    private UserFactory() {} // utility class - no instances

    public static User create(UserRole role) {
        return switch (role) {
            case BIDDER -> new Bidder();
            case SELLER -> new Seller();
            case ADMIN  -> new Admin();
        };
    }

    public static User create(String roleName) {
        return create(UserRole.valueOf(roleName.toUpperCase()));
    }
}
