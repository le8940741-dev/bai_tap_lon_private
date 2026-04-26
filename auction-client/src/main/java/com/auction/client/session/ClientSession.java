package com.auction.client.session;

import com.auction.client.network.ServerConnection; // the shared TCP connection
import com.auction.common.dto.UserDTO;              // the logged-in user's wire representation

/**
 * FILE ROLE: Application-wide singleton that holds login state and the network connection.
 *
 * Every controller needs two things:
 *   1. The ServerConnection to send messages.
 *   2. The current user's id/role to build requests and decide which UI to show.
 *
 * ClientSession provides a single place to get both, avoiding the need to pass
 * them through every constructor or FXML loader call.
 *
 * PATTERN: Singleton (GoF)
 *   Double-checked locking, same pattern as AuctionEventBus and DatabaseManager.
 *
 * LIFECYCLE:
 *   - Created in ClientMain.start() before any UI is shown.
 *   - connection is set once (in ClientMain) and never changed.
 *   - currentUser is set on successful LOGIN and cleared on logout().
 *
 * USED BY: Every controller (via ClientSession.getInstance())
 */
public final class ClientSession {

    private static volatile ClientSession instance;

    private ServerConnection connection; // the TCP link to the server
    private UserDTO currentUser;         // null = not logged in

    private ClientSession() {}

    /** Double-checked locking singleton accessor. */
    public static ClientSession getInstance() {
        if (instance == null) {
            synchronized (ClientSession.class) {
                if (instance == null) instance = new ClientSession();
            }
        }
        return instance;
    }

    // ── Connection ────────────────────────────────────────────────────────────

    /** The shared ServerConnection — set once in ClientMain before the UI starts. */
    public ServerConnection getConnection() { return connection; }
    public void setConnection(ServerConnection connection) { this.connection = connection; }

    // ── Authentication state ──────────────────────────────────────────────────

    /** The UserDTO returned by a successful LOGIN response. Null if not logged in. */
    public UserDTO getCurrentUser() { return currentUser; }
    public void setCurrentUser(UserDTO user) { this.currentUser = user; }

    /** True if a user has successfully logged in. */
    public boolean isLoggedIn() { return currentUser != null; }

    /**
     * Role-check helpers — used by controllers to decide what to show/hide.
     * Bidder: can bid, sees auction list.
     * Seller: can create items/auctions, sees seller dashboard.
     * Admin:  can ban users, sees admin panel.
     */
    public boolean isBidder() { return isLoggedIn() && "BIDDER".equals(currentUser.getRole()); }
    public boolean isSeller() { return isLoggedIn() && "SELLER".equals(currentUser.getRole()); }
    public boolean isAdmin()  { return isLoggedIn() && "ADMIN".equals(currentUser.getRole()); }

    /**
     * Clear the current user on logout.
     * The connection is kept open — the user may log in again without reconnecting.
     */
    public void logout() { currentUser = null; }
}
