package com.auction.server.model;

import java.time.LocalDateTime; // wall-clock timestamps for start/end time and creation

/**
 * Aggregate root for bidding: embeds an {@link Item}, tracks money + status + leader snapshot fields.
 *
 * <p><b>Volatile fields:</b> {@code currentPrice}, {@code endTime}, {@code status}, and leader ids are
 * {@code volatile} so the auction scheduler thread sees fresh values after {@link com.auction.server.service.BidService}
 * mutates them under locks — study the README concurrency section for the full story.</p>
 */
public final class Auction extends Entity {

    private Item item;                         // the item being sold - embedded, not just an id
    private double startingPrice;              // bid floor set at creation; never changes
    private volatile double currentPrice;      // highest bid so far; updated under ReentrantLock
    private LocalDateTime startTime;           // when OPEN -> RUNNING transition is allowed
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

    public boolean isActive() {
        return status == AuctionStatus.RUNNING;
    }

    // Getters / setters

    public Item getItem() { return item; }
    public void setItem(Item item) { this.item = item; }

    public double getStartingPrice() { return startingPrice; }
    public void setStartingPrice(double startingPrice) { this.startingPrice = startingPrice; }

    public double getCurrentPrice() { return currentPrice; }
    public void setCurrentPrice(double currentPrice) { this.currentPrice = currentPrice; }

    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }

    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }

    public AuctionStatus getStatus() { return status; }
    public void setStatus(AuctionStatus status) { this.status = status; }

    public long getSellerId() { return sellerId; }
    public void setSellerId(long sellerId) { this.sellerId = sellerId; }

    public String getSellerName() { return sellerName; }
    public void setSellerName(String sellerName) { this.sellerName = sellerName; }

    public Long getLeadingBidderId() { return leadingBidderId; }
    public void setLeadingBidderId(Long leadingBidderId) { this.leadingBidderId = leadingBidderId; }

    public String getLeadingBidderName() { return leadingBidderName; }
    public void setLeadingBidderName(String name) { this.leadingBidderName = name; }
}
