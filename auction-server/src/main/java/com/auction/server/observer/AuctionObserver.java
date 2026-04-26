package com.auction.server.observer;

import com.auction.server.model.Auction;      // the auction whose state changed
import com.auction.server.model.BidTransaction; // the bid that triggered the event

/**
 * FILE ROLE: Observer interface — the contract that any "watcher" must fulfill.
 *
 * PATTERN: Observer (GoF)
 *   The classic Observer pattern has two roles:
 *     - Subject (Observable): the thing that changes state — here, the auction.
 *     - Observer: anything that wants to be notified of state changes.
 *
 *   In this system:
 *     Subject  = AuctionEventBus (holds the watcher sets, publishes events)
 *     Observer = ClientHandler   (implements this interface; one per connected client)
 *
 * HOW IT WORKS:
 *   1. A client sends WATCH_AUCTION → ClientHandler calls eventBus.subscribe(auctionId, this).
 *   2. When BidService places a bid, it calls eventBus.publishBidPlaced(auction, bid).
 *   3. AuctionEventBus iterates the watcher set and calls observer.onBidPlaced() on each.
 *   4. ClientHandler.onBidPlaced() serialises a BID_BROADCAST Message and writes it
 *      to the client's TCP output stream.
 *
 * WHY AN INTERFACE (not abstract class):
 *   ClientHandler already extends nothing but needs to implement this contract.
 *   Java interfaces let a class fulfill multiple contracts simultaneously.
 *   If we ever add a logging observer or a metrics observer, they just implement
 *   this interface without changing any service code.
 *
 * THREADING:
 *   These callbacks are invoked on AuctionEventBus's notification thread pool,
 *   NOT on the client's reader thread.  ClientHandler.onBidPlaced() writes to
 *   the socket using a synchronized send() method to prevent interleaving.
 */
public interface AuctionObserver {

    /**
     * Called when a new valid bid is placed (manual or auto-bid).
     * Implementors should send a BID_BROADCAST message to the client.
     *
     * @param auction the auction's state AFTER the bid was applied (updated price/leader)
     * @param bid     the bid transaction that was just persisted
     */
    void onBidPlaced(Auction auction, BidTransaction bid);

    /**
     * Called when an auction's scheduler fires and closeAuction() completes.
     * Implementors should send an AUCTION_END_BROADCAST message to the client.
     *
     * @param auction the auction in its final FINISHED (or CANCELED) state
     */
    void onAuctionEnded(Auction auction);

    /**
     * Called when the anti-sniping algorithm extends the auction's end time.
     * Implementors should send an AUCTION_EXTENDED message so the client's
     * countdown timer updates to the new end time.
     *
     * @param auction the auction with its updated (extended) endTime
     */
    void onAuctionExtended(Auction auction);
}
