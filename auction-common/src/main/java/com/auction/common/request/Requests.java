package com.auction.common.request;

import java.util.List;

/**
 * Nested “request body” types the JavaFX client sends inside a {@link com.auction.common.protocol.Message}.
 *
 * <p><b>Why static nested classes?</b> They are grouped under {@code Requests} so your IDE
 * auto-import shows {@code Requests.LoginRequest} — one file, many small POJOs, no extra packages.</p>
 *
 * <p><b>Gson:</b> Fields must be {@code public} (or have accessors with the right names) so
 * Gson can populate them from JSON without custom adapters.</p>
 *
 * <p><b>Pairing:</b> Each class lines up with one {@link com.auction.common.protocol.MessageType}
 * constant documented in that enum (for example {@code PLACE_BID} + {@link PlaceBidRequest}).</p>
 */
public final class Requests {

    // Utility class - no instances.
    private Requests() {}

    // Authentication

    public static final class LoginRequest {
        public String username; // the account username (case-sensitive)
        public String password; // plaintext - hashed on the server, never stored as-is

        public LoginRequest() {}
        public LoginRequest(String u, String p) { username = u; password = p; }
    }

    public static final class RegisterRequest {
        public String username; // must be unique across all users
        public String password; // minimum 4 characters (validated by UserService)
        public String email;    // must contain "@" (minimal validation)
        public String role;     // "BIDDER" or "SELLER" only

        public RegisterRequest() {}
        public RegisterRequest(String u, String p, String e, String r) {
            username = u; password = p; email = e; role = r;
        }
    }

    // Bidding

    public static final class PlaceBidRequest {
        public long auctionId; // which auction to bid on
        public double amount;  // proposed bid amount in the auction's currency

        public PlaceBidRequest() {}
        public PlaceBidRequest(long auctionId, double amount) {
            this.auctionId = auctionId; this.amount = amount;
        }
    }

    public static final class SetAutoBidRequest {
        public long auctionId; // which auction to auto-bid on
        public double maxBid;  // will never bid above this ceiling
        public double increment; // how much to add to the competitor's bid each time

        public SetAutoBidRequest() {}
        public SetAutoBidRequest(long auctionId, double maxBid, double increment) {
            this.auctionId = auctionId; this.maxBid = maxBid; this.increment = increment;
        }
    }

    // Item management

    public static final class CreateItemRequest {
        public String name;        // display name shown in the auction list
        public String description; // longer text about the item
        public String category;    // "ELECTRONICS", "ART", or "VEHICLE"
        public String imageUrl;    // optional image URL or local file path
        public String extraData;   // optional JSON blob for category-specific fields
                                   // e.g. {"brand":"Sony","warranty":"2yr"} for electronics

        public CreateItemRequest() {}
    }

    public static final class CreateAuctionRequest {
        public long itemId;        // ID of an item the seller already created
        public double startingPrice; // opening bid floor; bids must exceed this
        public String startTime;   // requested start timestamp used for initial status/display
        public String endTime;     // when the auction auto-closes (scheduler fires)

        public CreateAuctionRequest() {}
    }

    // Auction subscription

    public static final class WatchAuctionRequest {
        public long auctionId; // auction to subscribe/unsubscribe from

        public WatchAuctionRequest() {}
        public WatchAuctionRequest(long id) { this.auctionId = id; }
    }

    public static final class GetAuctionDetailRequest {
        public long auctionId;

        public GetAuctionDetailRequest() {}
        public GetAuctionDetailRequest(long id) { this.auctionId = id; }
    }

    public static final class GetBidHistoryRequest {
        public long auctionId;

        public GetBidHistoryRequest() {}
        public GetBidHistoryRequest(long id) { this.auctionId = id; }
    }

    public static final class CancelAuctionRequest {
        public long auctionId;

        public CancelAuctionRequest() {}
        public CancelAuctionRequest(long id) { this.auctionId = id; }
    }

    public static final class BanUserRequest {
        public long userId;

        public BanUserRequest() {}
        public BanUserRequest(long id) { this.userId = id; }
    }
}
