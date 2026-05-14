package com.auction.server.model;

import java.time.LocalDateTime; // registration timestamp used for tie-breaking

/**
 * Proxy-bidding preferences for one bidder in one auction row.
 *
 * <p>{@link com.auction.server.service.BidService} reads active rows to fight other bidders up to
 * {@link #maxBid}. {@link #registeredAt} breaks ties fairly when two ceilings are identical.</p>
 */
public final class AutoBid extends Entity {

    private long auctionId;       // FK to auctions.id - which auction this applies to
    private long bidderId;        // FK to users.id - which Bidder registered this
    private String bidderName;    // denormalised for display and logging
    private double maxBid;        // the bidder will never auto-bid above this amount
    private double increment;     // how much to add to the competitor's bid per round
    private LocalDateTime registeredAt; // used for tie-breaking when two maxBids are equal
    private boolean active = true; // false = this auto-bid has been exhausted or cancelled

    public AutoBid() {
        super();
        this.registeredAt = LocalDateTime.now();
    }

    @Override
    public void printInfo() {
        System.out.printf("[AUTOBID] id=%d  auction=%d  bidder=%-15s  max=%.2f  inc=%.2f  active=%s%n",
                id, auctionId, bidderName, maxBid, increment, active);
    }

    // Getters / setters

    public long getAuctionId() { return auctionId; }
    public void setAuctionId(long auctionId) { this.auctionId = auctionId; }

    public long getBidderId() { return bidderId; }
    public void setBidderId(long bidderId) { this.bidderId = bidderId; }

    public String getBidderName() { return bidderName; }
    public void setBidderName(String bidderName) { this.bidderName = bidderName; }

    public double getMaxBid() { return maxBid; }
    public void setMaxBid(double maxBid) { this.maxBid = maxBid; }

    public double getIncrement() { return increment; }
    public void setIncrement(double increment) { this.increment = increment; }

    public LocalDateTime getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(LocalDateTime registeredAt) { this.registeredAt = registeredAt; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
