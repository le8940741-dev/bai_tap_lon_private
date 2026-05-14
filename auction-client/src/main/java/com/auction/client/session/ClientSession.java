package com.auction.client.session;

import com.auction.client.network.ServerConnection;
import com.auction.common.dto.UserDTO;

/**
 * Singleton “glue” object that every FXML controller can reach without constructor wiring.
 *
 * <p><b>Holds what?</b> The live {@link com.auction.client.network.ServerConnection} (TCP link)
 * and, after login, the {@link UserDTO} returned by the server. Role helpers ({@code isBidder()},
 * etc.) drive which buttons and screens are legal.</p>
 *
 * <p><b>Pattern note:</b> Same double-checked locking idea as the server-side {@code DatabaseManager}
 * class — one global instance per client JVM. For a larger app you might inject a session object instead.</p>
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

    // Connection

    /** Shared server connection, set during client startup. */
    public ServerConnection getConnection() { return connection; }
    public void setConnection(ServerConnection connection) { this.connection = connection; }

    // Authentication state

    /** The UserDTO returned by a successful LOGIN response. Null if not logged in. */
    public UserDTO getCurrentUser() { return currentUser; }
    public void setCurrentUser(UserDTO user) { this.currentUser = user; }

    /** True if a user has successfully logged in. */
    public boolean isLoggedIn() { return currentUser != null; }

    /**
     * Simple role checks used by controllers.
     */
    public boolean isBidder() { return isLoggedIn() && "BIDDER".equals(currentUser.getRole()); }
    public boolean isSeller() { return isLoggedIn() && "SELLER".equals(currentUser.getRole()); }
    public boolean isAdmin()  { return isLoggedIn() && "ADMIN".equals(currentUser.getRole()); }

    /**
     * Clear the current user on logout.
     * The connection stays open so another user can log in without reconnecting.
     */
    public void logout() { currentUser = null; }
}
