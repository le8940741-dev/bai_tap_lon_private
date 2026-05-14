package com.auction.server.exception;

/**
 * Checked-by-convention runtime exception for login / registration / role violations.
 *
 * <p>{@link com.auction.server.network.ClientHandler} catches these and turns {@link #getMessage()}
 * into an {@link com.auction.common.protocol.MessageType#ERROR} line for the client UI.</p>
 */
public class AuthException extends RuntimeException {
    public AuthException(String message) { super(message); }
    public AuthException(String message, Throwable cause) { super(message, cause); }
}
