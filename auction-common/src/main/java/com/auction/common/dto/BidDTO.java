package com.auction.common.dto;

/**
 * FILE ROLE: Wire representation of a single placed bid.
 *
 * Travels in two scenarios:
 *   1. As part of BidHistoryResponse (a full list loaded when entering detail screen).
 *   2. As part of BidResponse inside a BID_BROADCAST (live update to all watchers).
 *
 * The dual timestamp fields (String + long millis) serve different purposes:
 *   - 'timestamp' (ISO-8601 String) is for display: "14:32:07"
 *   - 'timestampMillis' (epoch milliseconds) is the X-axis value for the
 *     JavaFX LineChart — charts need numeric values, not strings.
 *
 * 'autoBid' lets the UI label a bid row as "AUTO" so users can tell whether
 * a bid was placed manually or by the auto-bidding algorithm.
 */
public class BidDTO {

    private long id;           // database primary key of the bid_transactions row
    private long auctionId;    // which auction this bid belongs to
    private long bidderId;     // which user placed the bid
    private String bidderName; // denormalised username for display without a lookup
    private double amount;     // the bid amount accepted by the server
    private String timestamp;  // ISO-8601 string: "2026-04-22T14:32:07" — for display
    private long timestampMillis; // epoch milliseconds — for the LineChart X-axis
    private boolean autoBid;   // true if BidService placed this automatically

    public BidDTO() {} // required by Gson

    // ── Getters ───────────────────────────────────────────────────────────────

    /** Database primary key of this bid record. */
    public long getId() { return id; }

    /** The auction this bid was placed in. Used to filter broadcasts by auction. */
    public long getAuctionId() { return auctionId; }

    /** The user who placed (or triggered via auto-bid) this bid. */
    public long getBidderId() { return bidderId; }

    /** The bidder's username — shown in the bid history table. */
    public String getBidderName() { return bidderName; }

    /** The accepted bid amount.  Always > the previous currentPrice. */
    public double getAmount() { return amount; }

    /** Human-readable timestamp string for display in the bid log table. */
    public String getTimestamp() { return timestamp; }

    /**
     * Epoch milliseconds used as the X-axis data point in the price chart.
     * JavaFX NumberAxis requires numeric values; we divide by 1000 to get seconds.
     */
    public long getTimestampMillis() { return timestampMillis; }

    /**
     * Whether this bid was placed by the auto-bid algorithm rather than directly
     * by the user.  Shown as "AUTO" in the Type column of the bid log table.
     */
    public boolean isAutoBid() { return autoBid; }

    // ── Setters (needed by Gson) ───────────────────────────────────────────────
    public void setId(long id) { this.id = id; }
    public void setAuctionId(long auctionId) { this.auctionId = auctionId; }
    public void setBidderId(long bidderId) { this.bidderId = bidderId; }
    public void setBidderName(String bidderName) { this.bidderName = bidderName; }
    public void setAmount(double amount) { this.amount = amount; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public void setTimestampMillis(long timestampMillis) { this.timestampMillis = timestampMillis; }
    public void setAutoBid(boolean autoBid) { this.autoBid = autoBid; }
}
