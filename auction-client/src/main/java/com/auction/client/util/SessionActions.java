package com.auction.client.util;

import com.auction.client.network.ServerConnection;
import com.auction.client.session.ClientSession;
import com.auction.common.protocol.Message;
import com.auction.common.protocol.MessageType;
import com.auction.common.request.EmptyPayload;

/**
 * Groups session-level UI actions that several controllers trigger.
 *
 * <p>Runtime flow: controllers call this utility from button handlers; it runs immediately on the
 * JavaFX application thread and sends any protocol message asynchronously through ServerConnection.</p>
 *
 * <p>Created/called by: no object creates this class; controllers call it directly, and it calls
 * ClientSession, ServerConnection, and SceneManager in the same order the controllers used before.</p>
 */
public final class SessionActions {

    private SessionActions() {}

    /**
     * Tell the server about logout, clear local session state, clear cached screens, and return to login.
     */
    public static void logoutToLogin() {
        ClientSession session = ClientSession.getInstance();
        ServerConnection conn = session.getConnection();
        conn.send(Message.of(MessageType.LOGOUT, EmptyPayload.INSTANCE, conn.getGson()));
        session.logout();
        SceneManager.evictAll();
        SceneManager.switchTo(SceneManager.View.LOGIN);
    }
}
