package com.auction.server.dao;

import com.auction.server.model.Auction;
import com.auction.server.model.AuctionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * DAO contract for auctions, including granular updates for hot fields (price, status, winner).
 *
 * <p>Splitting updates avoids rewriting entire rows when only one column changes — useful when
 * multiple threads touch bidding and scheduling logic.</p>
 */
public interface AuctionDAO {

    Auction save(Auction auction);

    Optional<Auction> findById(long id);

    List<Auction> findAll();

    List<Auction> findBySellerId(long sellerId);

    List<Auction> findByStatus(AuctionStatus status);

    void updateStatus(long auctionId, AuctionStatus status);

    void updateCurrentPrice(long auctionId, double price, long leadingBidderId);

    void updateEndTime(long auctionId, LocalDateTime newEndTime);

    void updateWinner(long auctionId, long winnerId, AuctionStatus status);
}
