package com.auction.common.dto;

/**
 * Composite wire object: auction header fields plus nested {@link ItemDTO} and leader snapshot strings.
 *
 * <p>Embedding {@link #item} avoids an extra round-trip when the list screen only needs titles — a
 * pragmatic denormalization for a course project.</p>
 */
public class AuctionDTO {

    private long id;            // database primary key
    private ItemDTO item;       // the item being auctioned (embedded, not a separate request)
    private double startingPrice; // the floor - bids must be strictly above this
    private double currentPrice;  // the highest bid so far (equals startingPrice if no bids)
    private String startTime;   // ISO-8601 when the auction opens for bidding
    private String endTime;     // ISO-8601 when the auction auto-closes
    private String status;      // "OPEN", "RUNNING", "FINISHED", "PAID", or "CANCELED"
    private long sellerId;      // the Seller who created this auction
    private String sellerName;  // denormalised for display
    private Long winnerId;      // null if no bids; otherwise the leading bidder's user id
    private String winnerName;  // null if no bids; otherwise the leading bidder's username
    private String createdAt;   // ISO-8601 when the auction record was created

    public AuctionDTO() {} // required by Gson

    // Getters

    /** Database primary key used in all API requests that reference this auction. */
    public long getId() { return id; }

    /**
     * The embedded item - name, description, category, extra data.
     * Embedding avoids a separate GET_AUCTION_DETAIL / GET_ITEM request
     * just to render the title and category in the list.
     */
    public ItemDTO getItem() { return item; }

    /** The floor price set by the seller at creation time. */
    public double getStartingPrice() { return startingPrice; }

    /**
     * The highest accepted bid amount, or startingPrice if no bids exist.
     * Displayed as the "Current Price" in the detail screen.
     * Updated atomically by BidService under a per-auction ReentrantLock.
     */
    public double getCurrentPrice() { return currentPrice; }

    /** ISO-8601 start time string. Used by the countdown timer and status display. */
    public String getStartTime() { return startTime; }

    /**
     * ISO-8601 end time string.
     * May change during the auction if anti-sniping fires - the client receives
     * an AUCTION_EXTENDED broadcast and updates its countdown accordingly.
     */
    public String getEndTime() { return endTime; }

    /**
     * Current lifecycle status as a string (matches AuctionStatus enum name).
     * The client uses this to enable/disable bid controls and show status badges.
     */
    public String getStatus() { return status; }

    /** Seller's user id - used to decide whether Cancel button is visible. */
    public long getSellerId() { return sellerId; }

    /** Seller's username - displayed in the detail screen. */
    public String getSellerName() { return sellerName; }

    /**
     * The current leading bidder's user id, or null if no bids.
     * After auction FINISHES, this becomes the winner's id.
     */
    public Long getWinnerId() { return winnerId; }

    /**
     * The current leading bidder's username, or null if no bids.
     * Displayed as "Leader: alice" or "Winner: alice" depending on status.
     */
    public String getWinnerName() { return winnerName; }

    /** ISO-8601 creation timestamp. */
    public String getCreatedAt() { return createdAt; }

    // Setters (needed by Gson)
    public void setId(long id) { this.id = id; }
    public void setItem(ItemDTO item) { this.item = item; }
    public void setStartingPrice(double startingPrice) { this.startingPrice = startingPrice; }
    public void setCurrentPrice(double currentPrice) { this.currentPrice = currentPrice; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }
    public void setStatus(String status) { this.status = status; }
    public void setSellerId(long sellerId) { this.sellerId = sellerId; }
    public void setSellerName(String sellerName) { this.sellerName = sellerName; }
    public void setWinnerId(Long winnerId) { this.winnerId = winnerId; }
    public void setWinnerName(String winnerName) { this.winnerName = winnerName; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
