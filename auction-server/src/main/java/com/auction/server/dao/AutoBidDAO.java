package com.auction.server.dao;

import com.auction.server.model.AutoBid;

import java.util.List;
import java.util.Optional;

/**
 * DAO contract for auto-bid rows (UPSERT semantics in SQLite implementation).
 */
public interface AutoBidDAO {

    AutoBid save(AutoBid autoBid);

    List<AutoBid> findActiveByAuctionId(long auctionId);

    Optional<AutoBid> findByAuctionAndBidder(long auctionId, long bidderId);

    void deactivate(long autoBidId);
}
