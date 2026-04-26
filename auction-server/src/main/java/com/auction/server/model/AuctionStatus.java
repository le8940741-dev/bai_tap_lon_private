package com.auction.server.model;

/**
 * FILE ROLE: Lifecycle states an auction passes through.
 *
 * The valid transitions are:
 *   OPEN → RUNNING    (startTime reached, or first bid placed)
 *   RUNNING → FINISHED (endTime reached, scheduler fires closeAuction())
 *   RUNNING → CANCELED (seller or admin calls cancelAuction())
 *   OPEN    → CANCELED (before any bids)
 *   FINISHED → PAID   (future: payment confirmed outside the system)
 *
 * Stored as TEXT in SQLite (matching the enum name exactly).
 *
 * Used in:
 *   - Auction.status — the current state.
 *   - AuctionService.createAuction() — sets initial status based on startTime.
 *   - AuctionService.closeAuction() — transitions to FINISHED or CANCELED.
 *   - BidService.placeBid() — rejects bids unless status is RUNNING.
 *   - AuctionListController — displayed in the Status column.
 *   - AuctionDetailController — controls whether bid controls are visible.
 */
public enum AuctionStatus {
    OPEN,      // created; startTime hasn't arrived yet; bids not yet accepted
    RUNNING,   // actively accepting bids; start time passed, end time hasn't
    FINISHED,  // auction closed; winner determined by highest bid
    PAID,      // winner has paid (placeholder for payment integration)
    CANCELED   // cancelled by seller or admin before finishing
}
