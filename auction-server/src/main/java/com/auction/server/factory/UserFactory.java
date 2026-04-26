package com.auction.server.factory;

import com.auction.server.model.Admin;    // concrete Admin subclass
import com.auction.server.model.Bidder;  // concrete Bidder subclass
import com.auction.server.model.Seller;  // concrete Seller subclass
import com.auction.server.model.User;    // abstract base returned by the factory
import com.auction.server.model.UserRole; // enum selecting which subclass to create

/**
 * FILE ROLE: Factory Method pattern for User creation.
 *
 * PATTERN: Factory Method (GoF)
 *   Mirrors ItemFactory — centralises instantiation of User subclasses so that
 *   adding a new role only requires one code change (here + a new subclass).
 *
 * USED BY:
 *   - UserService.register() — when a new account is created via RegisterRequest.
 *   - SQLiteUserDAO.map()    — when reading a user row from the database.
 *
 * WHY THE ADMIN CASE EXISTS:
 *   The Admin account is seeded directly by SQL in DatabaseManager, so register()
 *   rejects "ADMIN" as a role.  But SQLiteUserDAO needs to reconstruct the Admin
 *   object when reading the seeded row — that's the only legitimate caller of
 *   UserFactory.create(UserRole.ADMIN).
 */
public final class UserFactory {

    private UserFactory() {} // utility class — no instances

    /**
     * Create a new blank User of the specified role.
     * The caller is responsible for setting username, passwordHash, email, etc.
     *
     * @param role the role enum constant
     * @return a new, unpopulated User subclass instance
     */
    public static User create(UserRole role) {
        return switch (role) {
            case BIDDER -> new Bidder();
            case SELLER -> new Seller();
            case ADMIN  -> new Admin();
        };
    }

    /**
     * Convenience overload that accepts the role as a String.
     * Used by UserService.register() which receives the string from RegisterRequest.role.
     *
     * @param roleName e.g. "BIDDER" or "SELLER" (case-insensitive)
     * @throws IllegalArgumentException if the string doesn't match any enum constant
     */
    public static User create(String roleName) {
        return create(UserRole.valueOf(roleName.toUpperCase()));
    }
}
