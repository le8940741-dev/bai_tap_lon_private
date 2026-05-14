package com.auction.server.model;

/**
 * Abstract {@link User} in the <b>domain model</b> (pure Java — no SQL, no sockets).
 *
 * <p><b>Polymorphism:</b> Code stores {@code User user = ...} but the real object may be
 * {@link com.auction.server.model.Bidder}, {@link Seller}, or {@link Admin}. Each subclass
 * implements {@link #getRole()}, {@link #canBid()}, and {@link #canSell()} differently.
 * Services call these methods instead of checking strings, which keeps rules centralized.</p>
 *
 * <p><b>Why separate from {@code UserDTO}?</b> DTOs are safe JSON views for the network;
 * this class can hold {@code passwordHash} and never leaves the server process.</p>
 */
public abstract class User extends Entity {

    private String username;     // login name; unique across all users in the DB
    private String passwordHash; // never sent over the wire; only used by PasswordUtil.verify()
    private String email;        // contact address; must be unique in the DB
    private boolean active = true; // false = banned; server rejects login attempts

    protected User() { super(); }

    // Abstract role contract - each subclass defines its own answers

    public abstract UserRole getRole();

    public abstract boolean canBid();

    public abstract boolean canSell();

    // Polymorphic info display

    @Override
    public void printInfo() {
        // getRole() calls the subclass override - demonstrates runtime polymorphism.
        System.out.printf("[%s] id=%d  username=%-20s  email=%s  active=%s%n",
                getRole(), id, username, email, active);
    }

    // Getters / setters

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
