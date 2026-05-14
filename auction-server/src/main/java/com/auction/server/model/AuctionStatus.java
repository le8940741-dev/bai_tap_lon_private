package com.auction.server.model;

/**
 * Finite-state machine for auctions — {@link com.auction.server.service.AuctionService} advances these values.
 *
 * <p>Notice how both server logic and client UI can switch on the same names once they are copied into DTO strings.</p>
 */
public enum AuctionStatus {
    OPEN,      // created with a future start time; remains OPEN until a bid promotes it or it closes
    RUNNING,   // actively accepting bids; also reached when the first accepted bid lands
    FINISHED,  // auction closed; winner determined by highest bid
    PAID,      // winner has paid (placeholder for payment integration)
    CANCELED   // cancelled by seller or admin before finishing
}
