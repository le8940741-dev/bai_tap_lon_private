package com.auction.common.dto;

/**
 * FILE ROLE: The wire representation of a user account.
 *
 * WHY A SEPARATE DTO:
 *   The server-side User domain object (in auction-server) contains the
 *   passwordHash field.  We must never send that over the network.
 *   UserDTO deliberately omits it — only safe, public-facing fields are here.
 *
 *   Additionally, the client module does not depend on auction-server at all
 *   (it only depends on auction-common).  DTOs are the bridge: they live in
 *   the shared module so both sides speak the same language.
 *
 * USED BY:
 *   - Server: DtoMapper.toDto(User) converts a domain User → UserDTO before sending.
 *   - Client: ClientSession.currentUser stores the logged-in user as a UserDTO.
 *             Admin panel displays a list of UserDTOs in its TableView.
 */
public class UserDTO {

    private long id;        // auto-generated database primary key
    private String username; // the display name / login name
    private String email;    // contact address
    private String role;     // "BIDDER", "SELLER", or "ADMIN" — controls what the user can do
    private boolean active;  // false = banned; the user cannot log in

    public UserDTO() {} // required by Gson for deserialisation

    public UserDTO(long id, String username, String email, String role, boolean active) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.role = role;
        this.active = active;
    }

    // ── Getters (no setters on id/role to discourage mutation on the client side) ──

    /** Database-assigned primary key. */
    public long getId() { return id; }

    /** Login name and display name throughout the UI. */
    public String getUsername() { return username; }

    /** Email address (displayed in admin panel). */
    public String getEmail() { return email; }

    /**
     * String representation of the role enum.
     * The client uses this to decide which screens to show after login:
     *   "BIDDER" → auction list
     *   "SELLER" → seller dashboard
     *   "ADMIN"  → admin panel
     */
    public String getRole() { return role; }

    /**
     * False if an admin has banned this account.
     * The server rejects logins from inactive users with AuthException.
     */
    public boolean isActive() { return active; }

    // Setters needed by Gson when deserialising incoming JSON.
    public void setId(long id) { this.id = id; }
    public void setUsername(String username) { this.username = username; }
    public void setEmail(String email) { this.email = email; }
    public void setRole(String role) { this.role = role; }
    public void setActive(boolean active) { this.active = active; }
}
