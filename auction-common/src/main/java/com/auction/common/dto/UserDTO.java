package com.auction.common.dto;

/**
 * JSON-safe projection of a persisted {@code User} row for client consumption.
 * <p>
 * Security note: password hashes never leave the server process. Authentication results
 * are conveyed only indirectly via {@link #active} and the presence of this object inside
 * a successful login response.
 * </p>
 * <p>Role strings mirror {@code UserRole} enum names exactly so the JavaFX layer can switch
 * navigation without additional mapping tables.</p>
 */
public class UserDTO {

    /** Surrogate primary key from SQLite {@code users.id}. */
    private long id;
    /** Unique login identifier; compared case-sensitively on the server. */
    private String username;
    /** Stored for display and lightweight validation at registration time. */
    private String email;
    /** One of {@code "BIDDER"}, {@code "SELLER"}, {@code "ADMIN"} — mirrors enum name. */
    private String role;
    /**
     * When {@code false}, the account is banned: {@code UserService.login} rejects credentials
     * with {@code AuthException} and admin UI renders the user as inactive.
     */
    private boolean active;

    /** Gson no-arg constructor — required for reflective deserialization. */
    public UserDTO() {}

    /**
     * @param id        primary key
     * @param username  login name
     * @param email     contact email
     * @param role      stringified {@code UserRole}
     * @param active    false if administratively disabled
     */
    public UserDTO(long id, String username, String email, String role, boolean active) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.role = role;
        this.active = active;
    }

    /** @return database id; stable for the lifetime of the row */
    public long getId() { return id; }

    /** @return human-facing handle shown across auction UIs */
    public String getUsername() { return username; }

    /** @return email string as stored; not re-validated on the client */
    public String getEmail() { return email; }

    /**
     * @return string form of the user's authorization persona; determines which FXML flows load after login
     */
    public String getRole() { return role; }

    /** @return {@code true} if the user may authenticate; {@code false} if banned */
    public boolean isActive() { return active; }

    /** @param id surrogate key assigned by the database */
    public void setId(long id) { this.id = id; }
    /** @param username new login name — must stay unique server-side */
    public void setUsername(String username) { this.username = username; }
    /** @param email contact email */
    public void setEmail(String email) { this.email = email; }
    /** @param role stringified role label */
    public void setRole(String role) { this.role = role; }
    /** @param active ban flag mirrored from {@code users.active} */
    public void setActive(boolean active) { this.active = active; }
}
