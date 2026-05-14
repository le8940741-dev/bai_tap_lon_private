package com.auction.common.request;

/**
 * Gson-friendly “empty JSON object” used as a message body when no fields are needed.
 *
 * <p><b>Problem it solves:</b> If {@code payload} were {@code null}, some Gson paths throw
 * {@link NullPointerException}. Serializing {@link #INSTANCE} produces {@code {}} so both
 * sides always deserialize a real object.</p>
 *
 * <p><b>Where you see it:</b> Client and server use it for simple acknowledgements such as
 * {@code GET_AUCTIONS} or after {@code LOGOUT}, paired with the right {@link com.auction.common.protocol.MessageType}.</p>
 */
public final class EmptyPayload {

    // One shared object for the whole program — no need to make new ones.
    public static final EmptyPayload INSTANCE = new EmptyPayload();

    private EmptyPayload() {}
}
