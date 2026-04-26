package com.auction.server.model;

import java.time.LocalDateTime; // timestamp of when the bid was placed

/**
 * FILE ROLE: Immutable record of a single placed bid.
 *
 * Every accepted bid (manual or auto) creates one BidTransaction row in the
 * bid_transactions table.  These records are never updated or deleted —
 * they are the audit trail of everything that happened in an auction.
 *
 * 'autoBid' flag distinguishes between:
 *   - Manual bids: the user typed an amount and clicked "BID NOW"
 *   - Auto bids:   BidService's resolveAutoBids() algorithm placed the bid
 *                  automatically in response to a competitor's bid
 *
 * RELATIONSHIP TO AUCTION:
 *   Many BidTransactions belong to one Auction.
 *   The bid history is loaded by BidDAO.findByAuctionId() and displayed as a
 *   table + LineChart in AuctionDetailController.
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

    // ── Getters / setters ──────────────────────────────────────────────────────

    /** FK to auctions.id — links this bid to its parent auction. */
    public long getAuctionId() { return auctionId; }
    public void setAuctionId(long auctionId) { this.auctionId = auctionId; }

    /** FK to users.id — the bidder who placed this bid. */
    public long getBidderId() { return bidderId; }
    public void setBidderId(long bidderId) { this.bidderId = bidderId; }

    /** Bidder's username — denormalised to avoid extra joins in bid history queries. */
    public String getBidderName() { return bidderName; }
    public void setBidderName(String bidderName) { this.bidderName = bidderName; }

    /** The bid amount.  Always strictly greater than the auction's previous currentPrice. */
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }

    /**
     * The precise moment this bid was accepted.
     * Used as the X-axis value (converted to epoch millis in DtoMapper) for the price chart.
     */
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    /**
     * True when BidService.resolveAutoBids() placed this bid automatically.
     * False when the bidder clicked "BID NOW" in the UI.
     * Shown in the "Type" column of the bid log table.
     */
    public boolean isAutoBid() { return autoBid; }
    public void setAutoBid(boolean autoBid) { this.autoBid = autoBid; }
}
