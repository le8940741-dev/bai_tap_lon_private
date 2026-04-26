package com.auction.server.exception;

/**
 * FILE ROLE: Signals a Bid-domain business rule violation.
 *
 * Thrown by BidService when:
 *   - A non-Bidder tries to place a bid
 *   - Bid amount is not strictly greater than currentPrice
 *   - The leading bidder tries to bid on their own winning auction
 *   - Auction is FINISHED or CANCELED
 *   - Auction's end time has already passed
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
public class BidException extends RuntimeException {
    public BidException(String message) { super(message); }
    public BidException(String message, Throwable cause) { super(message, cause); }
}
