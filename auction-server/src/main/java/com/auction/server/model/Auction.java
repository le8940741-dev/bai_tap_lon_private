package com.auction.server.model;

import java.time.LocalDateTime; // wall-clock timestamps for start/end time and creation

/**
 * FILE ROLE: Central aggregate representing one auction session.
 *
 * An Auction ties an Item to a time window, a price, and potentially a winner.
 * It is the most frequently read and written object in the system.
 *
 * CONCURRENCY NOTES:
 *   Several fields are marked 'volatile'.  This means that when BidService writes
 *   currentPrice under its ReentrantLock, other threads reading the field (e.g. the
 *   scheduler thread deciding whether to extend the end time) always see the latest
 *   value — not a stale CPU cache copy.
 *
 *   'volatile' alone does NOT make compound operations atomic.  That's why BidService
 *   still uses a ReentrantLock: the check-then-set (is bid > currentPrice? → update)
 *   must be atomic.  'volatile' only guarantees visibility of individual reads/writes.
 *
 * LIFECYCLE (status field):
 *   OPEN      → created with a future startTime; no bids accepted yet
 *   RUNNING   → startTime passed OR first bid placed; bids now accepted
 *   FINISHED  → endTime reached (scheduler fires closeAuction()); winner set
 *   PAID      → (future) payment confirmed outside the system
 *   CANCELED  → seller or admin cancelled before FINISHED
 */
public final class Auction extends Entity {

    private Item item;                         // the item being sold — embedded, not just an id
    private double startingPrice;              // bid floor set at creation; never changes
    private volatile double currentPrice;      // highest bid so far; updated under ReentrantLock
    private LocalDateTime startTime;           // when OPEN → RUNNING transition is allowed
    private volatile LocalDateTime endTime;    // when the scheduler fires closeAuction(); may be extended by anti-sniping
    private volatile AuctionStatus status;     // current lifecycle state
    private long sellerId;                     // the Seller who created this auction
    private String sellerName;                 // denormalised for display
    private volatile Long leadingBidderId;     // null until first bid; becomes winner after FINISHED
    private volatile String leadingBidderName; // denormalised; avoids a join when broadcasting

    public Auction() { super(); }

    @Override
    public void printInfo() {
        System.out.printf("[AUCTION] id=%d  item=%-25s  price=%.2f  status=%s  ends=%s%n",
                id, item != null ? item.getName() : "N/A",
                currentPrice, status, endTime);
    }

    /**
     * Convenience check used in several places to guard bidding logic.
     * An auction is "active" (accepting bids) only when RUNNING.
     */
    public boolean isActive() {
        return status == AuctionStatus.RUNNING;
    }

    // ── Getters / setters ──────────────────────────────────────────────────────

    /** The item being auctioned — full object, not just an ID. */
    public Item getItem() { return item; }
    public void setItem(Item item) { this.item = item; }

    /** The floor price set at creation. Bids must strictly exceed this. */
    public double getStartingPrice() { return startingPrice; }
    public void setStartingPrice(double startingPrice) { this.startingPrice = startingPrice; }

    /** The current highest accepted bid amount. Volatile — safe to read from any thread. */
    public double getCurrentPrice() { return currentPrice; }
    public void setCurrentPrice(double currentPrice) { this.currentPrice = currentPrice; }

    /** When the auction becomes RUNNING (bids accepted). */
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }

    /**
     * When the auction auto-closes.
     * Volatile because the scheduler thread reads it and BidService (with lock) may extend it.
     */
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }

    /** Current lifecycle state. Volatile for cross-thread visibility. */
    public AuctionStatus getStatus() { return status; }
    public void setStatus(AuctionStatus status) { this.status = status; }

    public long getSellerId() { return sellerId; }
    public void setSellerId(long sellerId) { this.sellerId = sellerId; }

    public String getSellerName() { return sellerName; }
    public void setSellerName(String sellerName) { this.sellerName = sellerName; }

    /**
     * The current leading bidder's ID.  Null before any bids.
     * After FINISHED, this is the winner's ID.
     */
    public Long getLeadingBidderId() { return leadingBidderId; }
    public void setLeadingBidderId(Long leadingBidderId) { this.leadingBidderId = leadingBidderId; }

    /** The leading bidder's username for display and broadcast payloads. */
    public String getLeadingBidderName() { return leadingBidderName; }
    public void setLeadingBidderName(String name) { this.leadingBidderName = name; }
}
