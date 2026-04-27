package com.auction.common.request;

// These DTO imports are referenced only in Javadoc; the actual request classes
// are plain data holders with no DTO dependencies.
import com.auction.common.dto.AuctionDTO;
import com.auction.common.dto.BidDTO;
import com.auction.common.dto.ItemDTO;
import com.auction.common.dto.UserDTO;

import java.util.List;

/**
 * FILE ROLE: All request payload classes — the data the CLIENT sends to the SERVER.
 *
 * Every inner class here is a simple data bag (public fields, no logic).
 * We use public fields instead of private + getters because:
 *   1. Gson can read/write public fields directly without reflection tricks.
 *   2. These are pure data transfer objects — there is no invariant to protect.
 *   3. It keeps the classes short and readable.
 *
 * Each inner class maps 1-to-1 to a MessageType:
 *   LoginRequest      ↔  MessageType.LOGIN
 *   RegisterRequest   ↔  MessageType.REGISTER
 *   PlaceBidRequest   ↔  MessageType.PLACE_BID
 *   ... and so on.
 *
 * HOW THEY TRAVEL:
 *   Client wraps one of these in a Message:
 *     Message.of(MessageType.PLACE_BID, new PlaceBidRequest(3L, 250.0), gson)
 *   Server's ClientHandler reads the MessageType, then calls:
 *     PlaceBidRequest req = msg.parsePayload(gson, PlaceBidRequest.class)
 */
public final class Requests {

    // Utility class — no instances.
    private Requests() {}

    // ── Authentication ────────────────────────────────────────────────────────

    /**
     * Payload for MessageType.LOGIN.
     * The server will look up username and verify the password hash.
     */
    public static final class LoginRequest {
        public String username; // the account username (case-sensitive)
        public String password; // plaintext — hashed on the server, never stored as-is

        public LoginRequest() {}
        public LoginRequest(String u, String p) { username = u; password = p; }
    }

    /**
     * Payload for MessageType.REGISTER.
     * Role must be "BIDDER" or "SELLER" — clients cannot self-register as ADMIN.
     */
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

    // ── Bidding ───────────────────────────────────────────────────────────────

    /**
     * Payload for MessageType.PLACE_BID.
     * BidService will validate that amount > current price before accepting.
     */
    public static final class PlaceBidRequest {
        public long auctionId; // which auction to bid on
        public double amount;  // proposed bid amount in the auction's currency

        public PlaceBidRequest() {}
        public PlaceBidRequest(long auctionId, double amount) {
            this.auctionId = auctionId; this.amount = amount;
        }
    }

    /**
     * Payload for MessageType.SET_AUTO_BID.
     * The server will automatically outbid competitors up to maxBid, raising
     * by increment each time.  If two auto-bids exist, the higher maxBid wins;
     * ties broken by earliest registration time.
     */
    public static final class SetAutoBidRequest {
        public long auctionId; // which auction to auto-bid on
        public double maxBid;  // will never bid above this ceiling
        public double increment; // how much to add to the competitor's bid each time

        public SetAutoBidRequest() {}
        public SetAutoBidRequest(long auctionId, double maxBid, double increment) {
            this.auctionId = auctionId; this.maxBid = maxBid; this.increment = increment;
        }
    }

    // ── Item management ───────────────────────────────────────────────────────

    /**
     * Payload for MessageType.CREATE_ITEM.
     * Only Seller accounts can send this.
     */
    public static final class CreateItemRequest {
        public String name;        // display name shown in the auction list
        public String description; // longer text about the item
        public String category;    // "ELECTRONICS", "ART", or "VEHICLE"
        public String imageUrl;    // optional image URL or local file path
        public String extraData;   // optional JSON blob for category-specific fields
                                   // e.g. {"brand":"Sony","warranty":"2yr"} for electronics

        public CreateItemRequest() {}
    }

    /**
     * Payload for MessageType.CREATE_AUCTION.
     * The item must already exist (created via CREATE_ITEM).
     * startTime and endTime are ISO-8601 strings: "2026-04-22T15:30:00"
     */
    public static final class CreateAuctionRequest {
        public long itemId;        // ID of an item the seller already created
        public double startingPrice; // opening bid floor; bids must exceed this
        public String startTime;   // when the auction becomes RUNNING
        public String endTime;     // when the auction auto-closes (scheduler fires)

        public CreateAuctionRequest() {}
    }

    // ── Auction subscription ──────────────────────────────────────────────────

    /**
     * Payload for MessageType.WATCH_AUCTION and UNWATCH_AUCTION.
     * After watching, the client's connection is registered in AuctionEventBus
     * and will receive BID_BROADCAST, AUCTION_END_BROADCAST, and AUCTION_EXTENDED
     * messages without sending any further requests.
     */
    public static final class WatchAuctionRequest {
        public long auctionId; // auction to subscribe/unsubscribe from

        public WatchAuctionRequest() {}
        public WatchAuctionRequest(long id) { this.auctionId = id; }
    }

    /**
     * Payload for MessageType.GET_AUCTION_DETAIL.
     * Returns a full AuctionDTO including the embedded ItemDTO.
     */
    public static final class GetAuctionDetailRequest {
        public long auctionId;

        public GetAuctionDetailRequest() {}
        public GetAuctionDetailRequest(long id) { this.auctionId = id; }
    }

    /**
     * Payload for MessageType.GET_BID_HISTORY.
     * Returns all BidDTOs for the auction in ascending time order.
     * Used to populate the bid table and rebuild the price chart.
     */
    public static final class GetBidHistoryRequest {
        public long auctionId;

        public GetBidHistoryRequest() {}
        public GetBidHistoryRequest(long id) { this.auctionId = id; }
    }

    /**
     * Payload for MessageType.CANCEL_AUCTION.
     * Allowed for: the auction's own seller, or an Admin.
     * Not allowed once auction is FINISHED or PAID.
     */
    public static final class CancelAuctionRequest {
        public long auctionId;

        public CancelAuctionRequest() {}
        public CancelAuctionRequest(long id) { this.auctionId = id; }
    }

    /**
     * Payload for MessageType.BAN_USER.
     * Admin-only.  Sets the user's active flag to false; they cannot log in again.
     */
    public static final class BanUserRequest {
        public long userId;

        public BanUserRequest() {}
        public BanUserRequest(long id) { this.userId = id; }
    }
}
