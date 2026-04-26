package com.auction.server.dao;

import com.auction.server.model.Auction;
import com.auction.server.model.AuctionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * FILE ROLE: DAO interface for Auction persistence.
 *
 * The most method-rich DAO because auctions change state frequently:
 * price updates on every bid, status transitions on every lifecycle event,
 * and end-time changes on every anti-snipe extension.
 *
 * Fine-grained update methods (updateCurrentPrice, updateStatus, updateEndTime,
 * updateWinner) exist instead of a single save(Auction) to avoid re-writing
 * unchanged columns on every bid — important for correctness when multiple
 * threads could theoretically interleave updates (though the ReentrantLock
 * in BidService prevents this for price/leader).
 *
 * IMPLEMENTED BY: SQLiteAuctionDAO
 * USED BY: AuctionService, BidService
 */
public interface AuctionDAO {

    /**
     * Persist a new Auction record. Sets auction.id from the generated key.
     * The embedded Item must already be persisted (item.id must be set).
     */
    Auction save(Auction auction);

    /**
     * Fetch a single auction by primary key, with its embedded Item.
     * Returns Optional.empty() if the auction doesn't exist.
     * Called by BidService.placeBid() to get the current state before validating.
     */
    Optional<Auction> findById(long id);

    /**
     * Return all auctions in descending creation order (newest first).
     * Called by AuctionService.getAllAuctions() for the main auction list.
     */
    List<Auction> findAll();

    /**
     * Return all auctions belonging to a specific seller.
     * Called for the Seller Dashboard's "My Auctions" table.
     */
    List<Auction> findBySellerId(long sellerId);

    /**
     * Return all auctions in a specific lifecycle status.
     * Used by AuctionService.restoreSchedules() at server startup to find
     * OPEN and RUNNING auctions that need their close tasks rescheduled.
     */
    List<Auction> findByStatus(AuctionStatus status);

    /**
     * Update only the status column.
     * Called for CANCELED and RUNNING transitions that don't change price or winner.
     */
    void updateStatus(long auctionId, AuctionStatus status);

    /**
     * Update current_price and winner_id atomically in one SQL UPDATE.
     * Called by BidService.persistBid() after every accepted bid.
     * The two columns are always updated together — they must stay consistent.
     *
     * @param leadingBidderId the id of the user who placed the winning bid
     */
    void updateCurrentPrice(long auctionId, double price, long leadingBidderId);

    /**
     * Update the end_time column.
     * Called by AuctionService.applyAntiSnipe() when a late bid extends the auction.
     * The scheduler task is also rescheduled after this call.
     */
    void updateEndTime(long auctionId, LocalDateTime newEndTime);

    /**
     * Set the final winner and transition to FINISHED (or CANCELED if no bids).
     * Called by AuctionService.closeAuction() when the scheduled task fires.
     *
     * @param winnerId the leading bidder's id (the auction's winner)
     * @param status   FINISHED (has winner) or CANCELED (no bids)
     */
    void updateWinner(long auctionId, long winnerId, AuctionStatus status);
}
