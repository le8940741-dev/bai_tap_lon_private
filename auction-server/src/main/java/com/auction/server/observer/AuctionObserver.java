package com.auction.server.observer;

import com.auction.server.model.Auction;
import com.auction.server.model.BidTransaction;

/**
 * Observer interface in the <b>Observer pattern</b> for live auction activity.
 *
 * <p><b>Who implements it:</b> {@link com.auction.server.network.ClientHandler} — one
 * instance per TCP connection. When the user opens an auction detail screen, the client
 * sends {@code WATCH_AUCTION}; the handler calls {@link AuctionEventBus#subscribe(long, AuctionObserver)}
 * so this socket receives push events.</p>
 *
 * <p><b>Who calls it:</b> {@link AuctionEventBus} after {@link com.auction.server.service.BidService}
 * accepts a bid or {@link com.auction.server.service.AuctionService} closes / extends an auction.
 * That way services stay unaware of sockets; they only talk to the bus.</p>
 */
public interface AuctionObserver {

    /** A new winning bid was stored; listeners should send {@code BID_BROADCAST} JSON. */
    void onBidPlaced(Auction auction, BidTransaction bid);

    /** Auction reached a terminal running state; listeners send {@code AUCTION_END_BROADCAST}. */
    void onAuctionEnded(Auction auction);

    /** Anti-snipe moved {@code endTime}; listeners send {@code AUCTION_EXTENDED}. */
    void onAuctionExtended(Auction auction);
}
