package com.auction.common.protocol;

/**
 * FILE ROLE: Defines every possible message category that can travel over the TCP wire.
 *
 * Both the server (ClientHandler) and the client (ServerConnection) switch on this
 * enum to decide how to deserialise and handle an incoming Message.  Every time you
 * add a new feature you add a new enum constant here first, then handle it in both ends.
 *
 * Think of MessageType as the "verb" of the protocol: Message is the envelope,
 * MessageType is the action word written on the front, and the payload is the body.
 */
public enum MessageType {

    // ── Authentication ────────────────────────────────────────────────────────
    // Sent by the client to create or verify identity.
    LOGIN,           // client → server: send username + password
    LOGIN_RESPONSE,  // server → client: returns UserDTO on success
    REGISTER,        // client → server: send username, password, email, role
    REGISTER_RESPONSE, // server → client: returns created UserDTO
    LOGOUT,          // client → server: teardown session; server clears watchers

    // ── Auction browsing (read-only queries) ───────────────────────────────────
    GET_AUCTIONS,        // client → server: fetch all auctions (no payload needed)
    AUCTIONS_RESPONSE,   // server → client: list of AuctionDTOs
    GET_AUCTION_DETAIL,  // client → server: fetch one auction by id
    AUCTION_DETAIL_RESPONSE, // server → client: single AuctionDTO
    GET_BID_HISTORY,     // client → server: fetch all bids for an auction
    BID_HISTORY_RESPONSE,    // server → client: list of BidDTOs

    // ── Bidding actions ────────────────────────────────────────────────────────
    PLACE_BID,      // client → server: manual bid with amount
    BID_RESPONSE,   // server → client: confirms the bid and echoes updated auction
    SET_AUTO_BID,   // client → server: register maxBid + increment for auto-bidding
    AUTO_BID_RESPONSE, // server → client: confirms auto-bid registration

    // ── Item and auction management (Seller / Admin only) ─────────────────────
    CREATE_ITEM,    // client → server: create a new item to be auctioned
    ITEM_CREATED,   // server → client: returns the persisted ItemDTO
    CREATE_AUCTION, // client → server: open an auction for an existing item
    AUCTION_CREATED,// server → client: returns the persisted AuctionDTO
    CANCEL_AUCTION, // client → server: cancel an open/running auction
    AUCTION_CANCELED, // server → client: confirms cancellation
    GET_SELLER_AUCTIONS,    // client → server: fetch auctions owned by logged-in seller
    SELLER_AUCTIONS_RESPONSE, // server → client: list of seller's AuctionDTOs
    GET_SELLER_ITEMS,       // client → server: fetch items created by logged-in seller
    SELLER_ITEMS_RESPONSE,  // server → client: list of seller's ItemDTOs

    // ── Admin operations ───────────────────────────────────────────────────────
    GET_USERS,   // client → server: fetch all registered users (admin only)
    USERS_RESPONSE, // server → client: list of UserDTOs
    BAN_USER,    // client → server: deactivate a user account
    USER_BANNED, // server → client: confirms ban

    // ── Real-time subscription ─────────────────────────────────────────────────
    // After watching an auction, the client receives server-push broadcasts
    // without sending any further requests.
    WATCH_AUCTION,   // client → server: start receiving push events for this auction
    UNWATCH_AUCTION, // client → server: stop receiving push events

    // ── Server-push broadcasts (server → client, unsolicited) ─────────────────
    // These are sent by the server's AuctionEventBus when something changes.
    // They have a fresh requestId that does NOT match any pending client future.
    BID_BROADCAST,         // new valid bid placed; carries BidResponse payload
    AUCTION_END_BROADCAST, // auction reached FINISHED state; carries AuctionDTO
    AUCTION_EXTENDED,      // anti-sniping extended the end time; carries AuctionExtendedNotice

    // ── Error ──────────────────────────────────────────────────────────────────
    ERROR  // server → client: something went wrong; payload is ErrorResponse with message
}
