package com.auction.common.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.util.UUID;

/**
 * One JSON line on the wire, shared by the JavaFX client and the TCP server.
 *
 * <p><b>Parts:</b> {@code requestId} (string), {@code type} ({@link MessageType}),
 * and {@code payload} (JSON object). The client puts each outgoing request into a
 * {@code CompletableFuture} keyed by {@code requestId}; the server must echo the same id
 * on the matching reply so the client knows which future to complete.</p>
 *
 * <p><b>Generics:</b> {@link #parsePayload(Gson, Class)} is a type parameter method
 * ({@code <T> T}) — you pass {@code LoginRequest.class}, {@code AuctionDTO.class}, etc.,
 * and Gson returns that concrete type. The payload stays as {@link com.google.gson.JsonElement}
 * inside this class because Gson does not know the Java type until {@code type} is read.</p>
 *
 * <p><b>Factories:</b> {@link #of} creates a fresh id; {@link #reply} copies an existing id;
 * {@link #broadcast} is only a readable alias for {@code of} when the server pushes events.</p>
 */
public final class Message {

    // Random id. The server copies this id back on the reply so the client knows
    // which request finished. Broadcasts use a new random id because nobody is waiting.
    private String requestId;

    // What kind of message this is (login, place bid, error, etc.).
    private MessageType type;

    // The "body" of the message as JSON. We turn it into a proper class in parsePayload().
    private JsonElement payload;

    /**
     * Make a new message with a new id. Used for normal requests and for broadcasts.
     */
    public static Message of(MessageType type, Object payloadObj, Gson gson) {
        Message m = new Message();
        // UUID creates a unique-looking request id so many requests can be in flight at once.
        // The server copies this exact id into the reply, which lets the client match reply to request.
        m.requestId = UUID.randomUUID().toString();
        m.type = type;
        m.payload = gson.toJsonTree(payloadObj);
        return m;
    }

    /** Same as of() — the name just reminds us we are pushing an update, not answering a request. */
    public static Message broadcast(MessageType type, Object payloadObj, Gson gson) {
        return of(type, payloadObj, gson);
    }

    /**
     * Make a reply that belongs to an earlier request. The requestId must be the same
     * as the one the client sent, or the client will not find its waiting request.
     */
    public static Message reply(String requestId, MessageType type, Object payloadObj, Gson gson) {
        Message m = new Message();
        m.requestId = requestId;
        m.type = type;
        m.payload = gson.toJsonTree(payloadObj);
        return m;
    }

    public String getRequestId() { return requestId; }

    public MessageType getType() { return type; }

    public JsonElement getPayload() { return payload; }

    /** Turn the JSON payload into a Java object of the class you expect. */
    public <T> T parsePayload(Gson gson, Class<T> clazz) {
        return gson.fromJson(payload, clazz);
    }

    @Override
    public String toString() {
        return "Message{requestId='" + requestId + "', type=" + type + '}';
    }
}
