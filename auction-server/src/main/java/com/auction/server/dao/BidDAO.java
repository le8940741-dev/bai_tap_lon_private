package com.auction.server.dao;

import com.auction.server.model.BidTransaction;

import java.util.List;

/**
 * DAO contract for the append-only {@code bid_transactions} history used by charts and auditing.
 */
public interface BidDAO {

    BidTransaction save(BidTransaction bid);

    List<BidTransaction> findByAuctionId(long auctionId);
}
