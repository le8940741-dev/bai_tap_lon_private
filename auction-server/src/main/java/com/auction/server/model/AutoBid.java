package com.auction.server.model;

import java.time.LocalDateTime; // registration timestamp used for tie-breaking

/**
 * FILE ROLE: Stores one bidder's auto-bid configuration for one auction.
 *
 * When a Bidder clicks "SET AUTO-BID", the server persists an AutoBid row.
 * BidService reads all active AutoBid rows for an auction and feeds them into
 * a PriorityQueue to resolve who should auto-bid next and by how much.
 *
 * TIE-BREAKING RULE (per spec):
 *   If two auto-bids have the same maxBid, the one with the earlier
 *   registeredAt timestamp wins.  This is the "first come, first served" rule.
 *
 * UPSERT BEHAVIOUR:
 *   If the same bidder sets a new auto-bid for the same auction, it replaces the
 *   previous one (SQLiteAutoBidDAO uses ON CONFLICT DO UPDATE).  This lets
 *   the user raise their ceiling mid-auction.
 *
 * DEACTIVATION:
 *   When a bidder's maxBid is reached and they can no longer outbid their
 *   competitors, their AutoBid is deactivated (active=false) in the database.
 *   They must register a new auto-bid with a higher ceiling to participate again.
 */
public final class AutoBid extends Entity {

    private long auctionId;       // FK to auctions.id — which auction this applies to
    private long bidderId;        // FK to users.id — which Bidder registered this
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

    // ── Getters / setters ──────────────────────────────────────────────────────

    public long getAuctionId() { return auctionId; }
    public void setAuctionId(long auctionId) { this.auctionId = auctionId; }

    public long getBidderId() { return bidderId; }
    public void setBidderId(long bidderId) { this.bidderId = bidderId; }

    public String getBidderName() { return bidderName; }
    public void setBidderName(String bidderName) { this.bidderName = bidderName; }

    /** The ceiling — auto-bidding stops when nextBid would exceed this. */
    public double getMaxBid() { return maxBid; }
    public void setMaxBid(double maxBid) { this.maxBid = maxBid; }

    /** The step added to the current price when firing an auto-bid. */
    public double getIncrement() { return increment; }
    public void setIncrement(double increment) { this.increment = increment; }

    /**
     * The moment this auto-bid was registered.
     * If two bidders have equal maxBid, the one with the earlier registeredAt wins.
     */
    public LocalDateTime getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(LocalDateTime registeredAt) { this.registeredAt = registeredAt; }

    /**
     * Whether this auto-bid is still eligible to fire.
     * SQLiteAutoBidDAO.findActiveByAuctionId() only returns rows where active=1.
     */
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
