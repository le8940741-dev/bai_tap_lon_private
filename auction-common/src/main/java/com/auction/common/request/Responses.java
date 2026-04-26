package com.auction.common.request;

// DTO types that responses carry back to the client.
import com.auction.common.dto.AuctionDTO;
import com.auction.common.dto.BidDTO;
import com.auction.common.dto.ItemDTO;
import com.auction.common.dto.UserDTO;

import java.util.List; // Java's generic ordered list, used for batch response payloads

/**
 * FILE ROLE: All response payload classes — the data the SERVER sends back to the CLIENT.
 *
 * Mirror of Requests.java.  Each inner class is the return payload for one or more
 * MessageTypes.  The server's ClientHandler builds one of these, wraps it in a
 * Message.reply(...), and sends it.  The client's CompletableFuture resolves with
 * the Message, then calls parsePayload(gson, TheResponseClass.class).
 *
 * NAMING CONVENTION:
 *   *Response  — carries the result of a specific client request
 *   *Notice    — carries a server-initiated event (broadcast, not a reply)
 */
public final class Responses {

    private Responses() {} // utility class — no instances

    // ── Generic error wrapper ─────────────────────────────────────────────────

    /**
     * Payload for MessageType.ERROR.
     * Sent whenever the server catches an AuthException, BidException,
     * AuctionException, or any unexpected RuntimeException.
     * The client displays 'message' in the status label.
     */
    public static final class ErrorResponse {
        public String message; // human-readable description of what went wrong

        public ErrorResponse() {}
        public ErrorResponse(String msg) { this.message = msg; }
    }

    // ── Auction list responses ─────────────────────────────────────────────────

    /**
     * Payload for MessageType.AUCTIONS_RESPONSE and SELLER_AUCTIONS_RESPONSE.
     * Carries a list of AuctionDTOs (each embedding its ItemDTO).
     * The client's TableView is populated from this list.
     */
    public static final class AuctionsResponse {
        public List<AuctionDTO> auctions; // all auctions visible to the requester

        public AuctionsResponse() {}
        public AuctionsResponse(List<AuctionDTO> auctions) { this.auctions = auctions; }
    }

    /**
     * Payload for MessageType.SELLER_ITEMS_RESPONSE.
     * Carries all items created by the logged-in seller.
     * Used to populate the item ComboBox in the "Create Auction" form
     * so the seller can pick which item to auction.
     */
    public static final class ItemsResponse {
        public List<ItemDTO> items;

        public ItemsResponse() {}
        public ItemsResponse(List<ItemDTO> items) { this.items = items; }
    }

    /**
     * Payload for MessageType.BID_HISTORY_RESPONSE.
     * Carries all bids for one auction in ascending time order.
     * Used to:
     *   1. Populate the bid history TableView in AuctionDetailController.
     *   2. Rebuild the price LineChart from scratch when entering the detail screen.
     */
    public static final class BidHistoryResponse {
        public long auctionId;   // echoed back so the client can verify it matches
        public List<BidDTO> bids; // chronological list of all bids placed

        public BidHistoryResponse() {}
        public BidHistoryResponse(long auctionId, List<BidDTO> bids) {
            this.auctionId = auctionId; this.bids = bids;
        }
    }

    /**
     * Payload for MessageType.USERS_RESPONSE.
     * Admin-only.  Full list of every registered user with role and active status.
     */
    public static final class UsersResponse {
        public List<UserDTO> users;

        public UsersResponse() {}
        public UsersResponse(List<UserDTO> users) { this.users = users; }
    }

    // ── Bidding responses ──────────────────────────────────────────────────────

    /**
     * Payload for MessageType.BID_RESPONSE and MessageType.BID_BROADCAST.
     *
     * This class serves a dual purpose:
     *   1. Direct reply to the bidder confirming their bid was accepted.
     *   2. Broadcast to all watchers of the auction so they see the updated price.
     *
     * The 'auction' field carries the updated state (new currentPrice, new leader)
     * so the UI can refresh without a second round-trip.
     */
    public static final class BidResponse {
        public BidDTO bid;       // the bid that was just placed (amount, bidder, time)
        public AuctionDTO auction; // the auction's state AFTER the bid was applied

        public BidResponse() {}
        public BidResponse(BidDTO bid, AuctionDTO auction) {
            this.bid = bid; this.auction = auction;
        }
    }

    /**
     * Payload for MessageType.AUCTION_EXTENDED.
     * Sent to all watchers when the anti-sniping algorithm fires.
     * The client updates its countdown timer and end-time label.
     */
    public static final class AuctionExtendedNotice {
        public long auctionId;   // which auction was extended
        public String newEndTime; // the new ISO-8601 end time (e.g. "2026-04-22T20:01:00")

        public AuctionExtendedNotice() {}
        public AuctionExtendedNotice(long auctionId, String newEndTime) {
            this.auctionId = auctionId; this.newEndTime = newEndTime;
        }
    }

    /**
     * Payload for MessageType.AUTO_BID_RESPONSE.
     * Confirms that the server registered the auto-bid settings.
     * Echoes back the settings so the client can display them.
     */
    public static final class AutoBidResponse {
        public long auctionId;  // which auction the auto-bid applies to
        public double maxBid;   // the ceiling the auto-bidder will never exceed
        public double increment; // the step size for each automatic raise

        public AutoBidResponse() {}
        public AutoBidResponse(long auctionId, double maxBid, double increment) {
            this.auctionId = auctionId; this.maxBid = maxBid; this.increment = increment;
        }
    }
}
