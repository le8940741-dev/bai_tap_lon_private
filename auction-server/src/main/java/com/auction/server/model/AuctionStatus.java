package com.auction.server.model;

/**
 * FILE ROLE: Lifecycle states an auction passes through.
 *
 * The valid transitions are:
 *   OPEN -> RUNNING     (the first accepted bid arrives while the auction is still OPEN)
 *   RUNNING -> FINISHED (endTime reached and closeAuction() finds a winner)
 *   RUNNING -> CANCELED (seller/admin cancels it, or closeAuction() finds no bids)
 *   OPEN -> CANCELED    (seller/admin cancels it, or it reaches endTime with no bids)
 *   FINISHED -> PAID    (future: payment confirmed outside the system)
 *
 * Stored as TEXT in SQLite (matching the enum name exactly), which keeps the
 * DB easy to inspect manually and makes Gson/DTO mapping straightforward.
 *
 * Used in:
 *   - Auction.status: the current lifecycle state persisted in the DB.
 *   - AuctionService.createAuction(): chooses OPEN vs RUNNING at creation time.
 *   - AuctionService.closeAuction(): transitions to FINISHED or CANCELED.
 *   - BidService.placeBid(): blocks terminal states, then may promote OPEN to
 *     RUNNING on the first accepted bid. There is no separate timer that flips
 *     OPEN to RUNNING exactly at startTime.
 *   - AuctionListController and AuctionDetailController: display status and
 *     decide which actions should be visible to the user.
 */
public enum AuctionStatus {
    OPEN,      // created with a future start time; remains OPEN until a bid promotes it or it closes
    RUNNING,   // actively accepting bids; also reached when the first accepted bid lands
    FINISHED,  // auction closed; winner determined by highest bid
    PAID,      // winner has paid (placeholder for payment integration)
    CANCELED   // cancelled by seller or admin before finishing
}
