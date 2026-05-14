package com.auction.server.exception;

/**
 * Signals that a bid amount, timing, or auto-bid configuration breaks auction rules.
 *
 * <p>Extending {@link RuntimeException} keeps service methods clean (no {@code throws} clauses)
 * while still aborting the current request path.</p>
 */
public class BidException extends RuntimeException {
    public BidException(String message) { super(message); }
    public BidException(String message, Throwable cause) { super(message, cause); }
}
