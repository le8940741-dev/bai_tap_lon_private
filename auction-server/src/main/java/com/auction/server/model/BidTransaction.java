package com.auction.server.model;

import java.time.LocalDateTime; // timestamp of when the bid was placed

/**
 * One persisted raise in the bidding timeline (manual or automatic).
 *
 * <p>Acts as an event record for the UI chart: stores both human-readable names and numeric amounts.
 * {@link #autoBid} distinguishes algorithm-generated rows for the “AUTO” column in JavaFX.</p>
 */
public final class BidTransaction extends Entity {

    private long auctionId;    // which auction this bid was placed in
    private long bidderId;     // the Bidder who placed (or triggered) this bid
    private String bidderName; // denormalised so the history table loads without a JOIN
    private double amount;     // the accepted bid value (always > previous currentPrice)
    private LocalDateTime timestamp; // exact moment this bid was processed
    private boolean autoBid;   // true = placed by the auto-bid algorithm, not the user directly

    public BidTransaction() {
        super();
        // Default timestamp to now; DAO may override with the DB-stored value when reading.
        this.timestamp = LocalDateTime.now();
    }

    @Override
    public void printInfo() {
        System.out.printf("[BID] id=%d  auction=%d  bidder=%-15s  amount=%.2f  auto=%s  at=%s%n",
                id, auctionId, bidderName, amount, autoBid, timestamp);
    }

    // Getters / setters

    public long getAuctionId() { return auctionId; }
    public void setAuctionId(long auctionId) { this.auctionId = auctionId; }

    public long getBidderId() { return bidderId; }
    public void setBidderId(long bidderId) { this.bidderId = bidderId; }

    public String getBidderName() { return bidderName; }
    public void setBidderName(String bidderName) { this.bidderName = bidderName; }

    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public boolean isAutoBid() { return autoBid; }
    public void setAutoBid(boolean autoBid) { this.autoBid = autoBid; }
}
