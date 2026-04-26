package com.auction.server.dao;

import com.auction.server.model.BidTransaction;

import java.util.List;

/**
 * FILE ROLE: DAO interface for BidTransaction persistence.
 *
 * Bid transactions are append-only — they are never updated or deleted.
 * Every accepted bid creates exactly one new row in bid_transactions.
 *
 * IMPLEMENTED BY: SQLiteBidDAO
 * USED BY: BidService
 */
public interface BidDAO {

    /**
     * Persist a new bid record. Sets bid.id from the generated key.
     * Called by BidService.persistBid() for every accepted manual or auto bid.
     *
     * @param bid a fully populated BidTransaction
     * @return the same BidTransaction with id set
     */
    BidTransaction save(BidTransaction bid);

    /**
     * Return all bids for a given auction in ascending chronological order.
     * Used to:
     *   1. Populate the bid history table in AuctionDetailController.
     *   2. Rebuild the price LineChart X/Y data from scratch when entering
     *      the detail screen.
     *
     * The ascending order matters for the chart: each new point is later in
     * time than the previous, which produces a correctly left-to-right curve.
     *
     * @param auctionId the auction whose bid history to retrieve
     */
    List<BidTransaction> findByAuctionId(long auctionId);
}
