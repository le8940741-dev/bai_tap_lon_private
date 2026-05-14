package com.auction.common.request;

import com.auction.common.dto.AuctionDTO;
import com.auction.common.dto.BidDTO;
import com.auction.common.dto.ItemDTO;
import com.auction.common.dto.UserDTO;

import java.util.List;

/**
 * Nested types for JSON the <b>server sends back</b> (normal replies, errors, and broadcast bodies).
 *
 * <p><b>DTO fields:</b> Lists use {@code List<AuctionDTO>} etc. — generics tell Gson and the
 * compiler what element type to expect when you deserialize.</p>
 *
 * <p><b>Where they land:</b> The client’s {@code CompletableFuture} completes with a
 * {@link com.auction.common.protocol.Message}; controllers call {@code parsePayload(gson, X.class)}
 * for the matching nested class here.</p>
 */
public final class Responses {

    private Responses() {} // utility class - no instances

    // Generic error wrapper

    public static final class ErrorResponse {
        public String message; // human-readable description of what went wrong

        public ErrorResponse() {}
        public ErrorResponse(String msg) { this.message = msg; }
    }

    // Auction list responses

    public static final class AuctionsResponse {
        public List<AuctionDTO> auctions; // all auctions visible to the requester

        public AuctionsResponse() {}
        public AuctionsResponse(List<AuctionDTO> auctions) { this.auctions = auctions; }
    }

    public static final class ItemsResponse {
        public List<ItemDTO> items;

        public ItemsResponse() {}
        public ItemsResponse(List<ItemDTO> items) { this.items = items; }
    }

    public static final class BidHistoryResponse {
        public long auctionId;   // echoed back so the client can verify it matches
        public List<BidDTO> bids; // chronological list of all bids placed

        public BidHistoryResponse() {}
        public BidHistoryResponse(long auctionId, List<BidDTO> bids) {
            this.auctionId = auctionId; this.bids = bids;
        }
    }

    public static final class UsersResponse {
        public List<UserDTO> users;

        public UsersResponse() {}
        public UsersResponse(List<UserDTO> users) { this.users = users; }
    }

    // Bidding responses

    public static final class BidResponse {
        public BidDTO bid;       // the bid that was just placed (amount, bidder, time)
        public AuctionDTO auction; // the auction's state AFTER the bid was applied

        public BidResponse() {}
        public BidResponse(BidDTO bid, AuctionDTO auction) {
            this.bid = bid; this.auction = auction;
        }
    }

    public static final class AuctionExtendedNotice {
        public long auctionId;   // which auction was extended
        public String newEndTime; // the new ISO-8601 end time (e.g. "2026-04-22T20:01:00")

        public AuctionExtendedNotice() {}
        public AuctionExtendedNotice(long auctionId, String newEndTime) {
            this.auctionId = auctionId; this.newEndTime = newEndTime;
        }
    }

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
