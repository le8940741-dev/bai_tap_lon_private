package com.auction.server.exception;

/**
 * Covers illegal auction/item transitions (wrong status, wrong owner, bad timestamps).
 *
 * <p>Separating this from {@link BidException} helps students map each exception to the
 * service class that throws it while reading stack traces.</p>
 */
public class AuctionException extends RuntimeException {
    public AuctionException(String message) { super(message); }
    public AuctionException(String message, Throwable cause) { super(message, cause); }
}
