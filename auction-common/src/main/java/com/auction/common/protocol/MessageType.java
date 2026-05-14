package com.auction.common.protocol;

/**
 * Names every command and event in the auction protocol (like labels on packets).
 *
 * <p><b>When the program runs:</b> The client builds a {@link com.auction.common.protocol.Message}
 * with one of these values in {@code type}. Gson turns the whole object into text and
 * {@code println} sends it. On the server, {@code ClientHandler} switches on {@code type}
 * and calls the right service method. The same enum is used on the way back for responses,
 * errors, and live broadcasts.</p>
 *
 * <p><b>Why an {@code enum}?</b> The compiler checks that your {@code switch} covers known
 * values, and you cannot accidentally invent a new message name with a typo string.</p>
 */
public enum MessageType {

    // ----- Account stuff -----
    LOGIN,                  // client asks: log me in
    LOGIN_RESPONSE,       // server answers: here is your user (or you get ERROR)
    REGISTER,             // client asks: make a new account
    REGISTER_RESPONSE,    // server answers: account created
    LOGOUT,               // client says: I am leaving

    // ----- Looking at auctions (no bidding yet) -----
    GET_AUCTIONS,              // give me the list
    AUCTIONS_RESPONSE,         // here is the list
    GET_AUCTION_DETAIL,        // give me one auction in detail
    AUCTION_DETAIL_RESPONSE,   // here is that auction
    GET_BID_HISTORY,           // give me past bids for this auction
    BID_HISTORY_RESPONSE,      // here is the bid list

    // ----- Bidding -----
    PLACE_BID,         // I want to bid this amount
    BID_RESPONSE,      // ok, here is the new bid and updated auction
    SET_AUTO_BID,      // bid for me automatically up to a max
    AUTO_BID_RESPONSE, // ok, auto-bid is saved

    // ----- Seller: items and auctions -----
    CREATE_ITEM,               // add something to sell
    ITEM_CREATED,              // item saved
    CREATE_AUCTION,            // start an auction for an item
    AUCTION_CREATED,           // auction saved
    CANCEL_AUCTION,            // stop this auction
    AUCTION_CANCELED,          // auction is canceled
    GET_SELLER_AUCTIONS,       // my auctions as seller
    SELLER_AUCTIONS_RESPONSE,  // here they are
    GET_SELLER_ITEMS,          // my items as seller
    SELLER_ITEMS_RESPONSE,     // here they are

    // ----- Admin -----
    GET_USERS,         // list all users (admin only)
    USERS_RESPONSE,    // here is the list
    BAN_USER,          // turn off an account
    USER_BANNED,       // done

    // ----- Watching live updates -----
    WATCH_AUCTION,     // send me live updates for this auction
    UNWATCH_AUCTION,   // stop sending me updates

    // ----- Updates the server sends by itself (you did not ask for these) -----
    BID_BROADCAST,           // someone placed a bid
    AUCTION_END_BROADCAST,   // auction ended
    AUCTION_EXTENDED,      // end time was pushed later (anti-snipe)

    // ----- Something went wrong -----
    ERROR   // message text explains the problem
}
