package com.auction.server.dao;

import com.auction.server.model.AutoBid;

import java.util.List;
import java.util.Optional;

/**
 * FILE ROLE: DAO interface for AutoBid persistence.
 *
 * Auto-bid records are created when a Bidder registers an auto-bid,
 * and deactivated (active=false) when they can no longer compete
 * (their maxBid ceiling has been reached by a competitor's higher auto-bid).
 *
 * IMPLEMENTED BY: SQLiteAutoBidDAO
 * USED BY: BidService
 */
public interface AutoBidDAO {

    /**
     * Persist a new auto-bid, or update an existing one for the same
     * (auction_id, bidder_id) pair.
     *
     * The UPSERT behaviour (ON CONFLICT DO UPDATE in SQLite) lets the same
     * bidder raise their ceiling mid-auction without creating duplicate rows.
     * The unique constraint on (auction_id, bidder_id) enforces one auto-bid
     * per bidder per auction.
     *
     * @param autoBid a populated AutoBid with all fields set
     * @return the same AutoBid with id set (either new or existing row's id)
     */
    AutoBid save(AutoBid autoBid);

    /**
     * Return all ACTIVE auto-bids for a given auction, ordered by registration time.
     *
     * Called by BidService.resolveAutoBids() after every bid to determine
     * whether any auto-bidder should respond.  Only active=1 rows are returned —
     * exhausted or cancelled auto-bids are invisible to the algorithm.
     *
     * The chronological ordering is the tiebreaker when two auto-bids have
     * equal maxBid values: the PriorityQueue comparator also checks registeredAt,
     * but pre-ordering from the DB makes the comparison easier to reason about.
     */
    List<AutoBid> findActiveByAuctionId(long auctionId);

    /**
     * Find the auto-bid record for a specific bidder in a specific auction.
     * Returns Optional.empty() if the bidder has no auto-bid registered.
     * Used for diagnostic/display purposes (not currently in the main flow).
     */
    Optional<AutoBid> findByAuctionAndBidder(long auctionId, long bidderId);

    /**
     * Mark an auto-bid as inactive (active=0) without deleting it.
     * Called when the auto-bidder's maxBid has been reached — they can no longer
     * outbid the current leader.  The historical record is preserved.
     *
     * @param autoBidId the primary key of the auto-bid row to deactivate
     */
    void deactivate(long autoBidId);
}
