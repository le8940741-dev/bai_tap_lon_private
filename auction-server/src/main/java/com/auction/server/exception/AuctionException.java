package com.auction.server.exception;

/**
 * FILE ROLE: Signals a Auction-domain business rule violation.
 *
 * Thrown by AuctionService and ItemService when:
 *   - A non-Seller tries to create an auction
 *   - Starting price is negative or zero
 *   - End time is not after start time
 *   - Trying to cancel a FINISHED or PAID auction
 *   - Auction not found by id
 *
 * HOW IT IS HANDLED:
 *   ClientHandler's dispatch() method catches all three exception types in its
 *   try/catch block and converts them to an ERROR message with the exception's
 *   message text, which the client displays in the status label.
 *
 *   This means service methods never need to know about the network layer —
 *   they just throw and let the handler deal with it.
 *
 * EXTENDS RuntimeException:
 *   Unchecked so callers don't need to declare 'throws' everywhere.
 *   These are not recoverable programming errors — they are expected business
 *   rule violations that need to be reported to the user.
 */
public class AuctionException extends RuntimeException {
    public AuctionException(String message) { super(message); }
    public AuctionException(String message, Throwable cause) { super(message, cause); }
}
