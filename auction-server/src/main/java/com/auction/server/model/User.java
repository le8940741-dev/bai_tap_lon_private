package com.auction.server.model;

/**
 * FILE ROLE: Abstract domain model for all user account types.
 *
 * Extends Entity (gets id + createdAt).
 * Subclassed by Bidder, Seller, and Admin — each concrete class overrides the
 * three abstract permission methods to express what that role can do.
 *
 * ENCAPSULATION:
 *   'passwordHash' is private with no public getter that returns the raw value
 *   in a way that leaks it over the wire.  DtoMapper.toDto(User) deliberately
 *   omits the hash when creating a UserDTO — the hash never leaves the server.
 *
 * POLYMORPHISM IN ACTION:
 *   UserService.login() calls user.getRole() without knowing the concrete type.
 *   ClientHandler.handlePlaceBid() calls bidder.canBid() — returns true only
 *   for Bidder, false for Seller and Admin, without any instanceof checks.
 *
 * WHY ABSTRACT METHODS INSTEAD OF A ROLE CHECK:
 *   We could write:  if (user.getRole() == UserRole.BIDDER) { ... }
 *   But that requires every call site to know about roles.
 *   canBid() / canSell() push the knowledge into the class itself — that's
 *   the Open/Closed Principle: adding a new role means adding a new subclass,
 *   not editing every if-statement in the codebase.
 */
public abstract class User extends Entity {

    private String username;     // login name; unique across all users in the DB
    private String passwordHash; // never sent over the wire; only used by PasswordUtil.verify()
    private String email;        // contact address; must be unique in the DB
    private boolean active = true; // false = banned; server rejects login attempts

    protected User() { super(); }

    // ── Abstract role contract — each subclass defines its own answers ─────────

    /** Returns the role enum constant (BIDDER, SELLER, or ADMIN). */
    public abstract UserRole getRole();

    /**
     * Returns true only for Bidder.
     * Called before placing a bid or setting an auto-bid.
     * Sellers and Admins cannot participate in bidding.
     */
    public abstract boolean canBid();

    /**
     * Returns true only for Seller.
     * Called before creating items or auctions.
     * Bidders and Admins cannot list items.
     */
    public abstract boolean canSell();

    // ── Polymorphic info display ───────────────────────────────────────────────

    @Override
    public void printInfo() {
        // getRole() calls the subclass override — demonstrates runtime polymorphism.
        System.out.printf("[%s] id=%d  username=%-20s  email=%s  active=%s%n",
                getRole(), id, username, email, active);
    }

    // ── Getters / setters ──────────────────────────────────────────────────────

    /** Login name and display name throughout the UI. */
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    /**
     * The salted SHA-256 hash.  Never returned to the client.
     * Only PasswordUtil and SQLiteUserDAO call this getter.
     */
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    /** Email address. */
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    /**
     * Active status — false means the account has been banned by an admin.
     * UserService.login() rejects inactive users before checking the password.
     */
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
