package com.auction.common.request;

/**
 * FILE ROLE: A type-safe empty request body for messages that carry no data.
 *
 * WHY THIS EXISTS:
 *   Several messages (GET_AUCTIONS, LOGOUT, GET_USERS, GET_SELLER_AUCTIONS) need no
 *   input from the client — they just trigger an action.  We still need *something*
 *   to pass as the payload argument to Message.of(), because Gson will serialise it
 *   into the "payload" field of the JSON envelope.
 *
 *   If we pass a raw Java String like "{}" instead, Gson serialises it as a JSON
 *   string primitive: "payload": "{}" (a string containing braces).
 *   When the server tries to parse that field as an object, it gets a JsonPrimitive
 *   instead of a JsonObject and the parse fails or returns null fields.
 *
 *   By passing EmptyPayload.INSTANCE, Gson serialises it as: "payload": {}
 *   which is a proper empty JSON object — safe to ignore on the server side.
 *
 * USAGE:
 *   Message.of(MessageType.GET_AUCTIONS, EmptyPayload.INSTANCE, gson)
 */
public final class EmptyPayload {

    // Singleton instance — no need to ever create more than one.
    public static final EmptyPayload INSTANCE = new EmptyPayload();

    // Private constructor — callers must use the INSTANCE constant.
    private EmptyPayload() {}
}
