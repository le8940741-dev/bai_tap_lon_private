package com.auction.server.network;

// ── Wire types (shared with client) ───────────────────────────────────────────
import com.auction.common.dto.AuctionDTO;    // DTO carried in auction-related responses
import com.auction.common.dto.BidDTO;        // DTO carried in bid-related responses
import com.auction.common.protocol.Message;  // TCP envelope — every message is one of these
import com.auction.common.protocol.MessageType; // the "verb" of each message
// All request payload classes sent by the client:
import com.auction.common.request.Requests.*;
// All response payload classes sent back to the client:
import com.auction.common.request.Responses.*;

// ── Server-side exceptions ─────────────────────────────────────────────────────
import com.auction.server.exception.AuctionException;
import com.auction.server.exception.AuthException;
import com.auction.server.exception.BidException;

// ── Domain models ──────────────────────────────────────────────────────────────
import com.auction.server.model.*;   // Auction, AutoBid, BidTransaction, User, UserRole, Item

// ── Observer pattern ───────────────────────────────────────────────────────────
import com.auction.server.observer.AuctionEventBus; // the event bus this handler subscribes to
import com.auction.server.observer.AuctionObserver; // the interface this class implements

// ── Services ───────────────────────────────────────────────────────────────────
import com.auction.server.service.AuctionService;
import com.auction.server.service.BidService;
import com.auction.server.service.ItemService;
import com.auction.server.service.UserService;

// ── Utilities ──────────────────────────────────────────────────────────────────
import com.auction.server.util.DtoMapper; // converts domain objects → wire DTOs
import com.google.gson.Gson;              // JSON serialiser/deserialiser

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// ── Java I/O ───────────────────────────────────────────────────────────────────
import java.io.*;       // BufferedReader, InputStreamReader, PrintWriter, IOException
import java.net.Socket; // the TCP socket for this one client connection

// ── Java time ─────────────────────────────────────────────────────────────────
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// ── Java collections ──────────────────────────────────────────────────────────
import java.util.List;
import java.util.stream.Collectors; // stream().map().collect() for DTO list conversions

/**
 * FILE ROLE: Manages the full lifecycle of one client's TCP connection.
 *
 * This is the largest class in the server because it sits at the intersection
 * of three concerns that are inherently coupled for a per-connection handler:
 *   1. I/O:       reading messages from and writing messages to the TCP socket.
 *   2. Dispatch:  routing each MessageType to the correct service call.
 *   3. Observer:  receiving server-push events and forwarding them to the client.
 *
 * ONE INSTANCE PER CONNECTED CLIENT:
 *   AuctionServer creates a new ClientHandler for each accepted Socket and
 *   submits it to a cached thread pool.  The run() method blocks on readLine()
 *   until the client disconnects.
 *
 * IMPLEMENTS AuctionObserver:
 *   When a client sends WATCH_AUCTION, this handler registers itself with
 *   AuctionEventBus.  When BidService places a bid, the bus calls
 *   this.onBidPlaced() on a pool thread — the handler serialises a BID_BROADCAST
 *   Message and sends it over the socket.
 *
 * THREAD SAFETY:
 *   The send() method is 'synchronized' because two threads may try to write
 *   to the same socket simultaneously:
 *     - The reader thread (processing a client request) may produce a response.
 *     - The notify-pool thread (from AuctionEventBus) may produce a broadcast.
 *   Synchronized send() ensures the two JSON lines never interleave on the wire.
 *
 * AUTHENTICATION STATE:
 *   'currentUser' is null until a successful LOGIN.  Any handler method that
 *   requires authentication calls requireAuth() first, which throws AuthException
 *   if currentUser is null.  The dispatch() catch block converts that to an ERROR.
 */
public final class ClientHandler implements Runnable, AuctionObserver {

    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final Socket socket;         // the TCP connection for this client
    private final Gson gson;             // shared (thread-safe) JSON serialiser
    private final UserService userService;
    private final ItemService itemService;
    private final AuctionService auctionService;
    private final BidService bidService;
    private final AuctionEventBus eventBus = AuctionEventBus.getInstance();

    private PrintWriter out;       // writes JSON lines to the socket; set in run()
    private User currentUser;      // null = not logged in; set on successful LOGIN

    public ClientHandler(Socket socket, Gson gson,
                         UserService userService, ItemService itemService,
                         AuctionService auctionService, BidService bidService) {
        this.socket         = socket;
        this.gson           = gson;
        this.userService    = userService;
        this.itemService    = itemService;
        this.auctionService = auctionService;
        this.bidService     = bidService;
    }

    // ── Main I/O loop ─────────────────────────────────────────────────────────

    /**
     * Entry point for the thread pool task.
     *
     * Opens a BufferedReader on the socket's input stream.
     * readLine() blocks until a complete JSON line arrives (or the socket closes).
     * Each line is a complete JSON-serialised Message.
     * On disconnect (readLine returns null or IOException), unsubscribes from all
     * event bus watcher sets to prevent memory leaks and null pointer exceptions
     * during future broadcasts.
     */
    @Override
    public void run() {
        try (
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true)
            // 'true' = auto-flush: every println() immediately sends the line
        ) {
            this.out = out;
            String line;
            while ((line = in.readLine()) != null) { // blocks until data or disconnect
                try {
                    Message msg = gson.fromJson(line, Message.class);
                    dispatch(msg);
                } catch (Exception e) {
                    log.warn("Malformed message from {}: {}",
                            socket.getRemoteSocketAddress(), e.getMessage());
                    sendError(null, "Malformed message: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            log.info("Client disconnected: {}", socket.getRemoteSocketAddress());
        } finally {
            // Critical cleanup: remove this handler from ALL watcher sets.
            // Without this, the dead handler stays in the set and future
            // broadcasts to it silently fail or log warnings.
            eventBus.unsubscribeAll(this);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // ── Message dispatcher ────────────────────────────────────────────────────

    /**
     * Route each incoming Message to the appropriate handler method.
     *
     * All business exceptions (AuthException, BidException, AuctionException)
     * are caught here and converted to ERROR responses.  This keeps each handler
     * method clean — they just throw on failure, never build error responses themselves.
     *
     * Unexpected RuntimeExceptions are also caught and logged as "Internal server error"
     * so one bad request can't crash the entire client connection.
     */
    private void dispatch(Message msg) {
        try {
            switch (msg.getType()) {
                case REGISTER            -> handleRegister(msg);
                case LOGIN               -> handleLogin(msg);
                case LOGOUT              -> handleLogout(msg);
                case GET_AUCTIONS        -> handleGetAuctions(msg);
                case GET_AUCTION_DETAIL  -> handleGetAuctionDetail(msg);
                case GET_BID_HISTORY     -> handleGetBidHistory(msg);
                case PLACE_BID           -> handlePlaceBid(msg);
                case SET_AUTO_BID        -> handleSetAutoBid(msg);
                case CREATE_ITEM         -> handleCreateItem(msg);
                case CREATE_AUCTION      -> handleCreateAuction(msg);
                case CANCEL_AUCTION      -> handleCancelAuction(msg);
                case WATCH_AUCTION       -> handleWatchAuction(msg);
                case UNWATCH_AUCTION     -> handleUnwatchAuction(msg);
                case GET_SELLER_AUCTIONS -> handleGetSellerAuctions(msg);
                case GET_SELLER_ITEMS    -> handleGetSellerItems(msg);
                case GET_USERS           -> handleGetUsers(msg);
                case BAN_USER            -> handleBanUser(msg);
                default -> sendError(msg.getRequestId(),
                        "Unknown message type: " + msg.getType());
            }
        } catch (AuthException | BidException | AuctionException e) {
            // Expected business rule violations — send a clean error message to the client.
            sendError(msg.getRequestId(), e.getMessage());
        } catch (Exception e) {
            // Unexpected errors — log the full stack trace server-side, send generic message.
            log.error("Unhandled error processing {}", msg.getType(), e);
            sendError(msg.getRequestId(), "Internal server error");
        }
    }

    // ── Auth handlers ─────────────────────────────────────────────────────────

    /** Parse RegisterRequest, call UserService.register(), reply with the new UserDTO. */
    private void handleRegister(Message msg) {
        RegisterRequest req = msg.parsePayload(gson, RegisterRequest.class);
        User user = userService.register(req.username, req.password, req.email, req.role);
        send(Message.reply(msg.getRequestId(), MessageType.REGISTER_RESPONSE,
                DtoMapper.toDto(user), gson));
    }

    /**
     * Parse LoginRequest, call UserService.login(), store the returned User in
     * currentUser, reply with the UserDTO (role drives UI navigation on the client).
     */
    private void handleLogin(Message msg) {
        LoginRequest req = msg.parsePayload(gson, LoginRequest.class);
        currentUser = userService.login(req.username, req.password);
        send(Message.reply(msg.getRequestId(), MessageType.LOGIN_RESPONSE,
                DtoMapper.toDto(currentUser), gson));
    }

    /** Unsubscribe from all auctions, clear currentUser, send acknowledgment. */
    private void handleLogout(Message msg) {
        eventBus.unsubscribeAll(this);
        currentUser = null;
        send(Message.reply(msg.getRequestId(), MessageType.LOGOUT, "OK", gson));
    }

    // ── Auction query handlers ─────────────────────────────────────────────────

    /** Return all auctions as a list of AuctionDTOs (no auth required — public list). */
    private void handleGetAuctions(Message msg) {
        List<AuctionDTO> dtos = auctionService.getAllAuctions().stream()
                .map(DtoMapper::toDto)      // convert each Auction → AuctionDTO
                .collect(Collectors.toList());
        send(Message.reply(msg.getRequestId(), MessageType.AUCTIONS_RESPONSE,
                new AuctionsResponse(dtos), gson));
    }

    /** Fetch one auction by id and return the full AuctionDTO (with embedded ItemDTO). */
    private void handleGetAuctionDetail(Message msg) {
        GetAuctionDetailRequest req = msg.parsePayload(gson, GetAuctionDetailRequest.class);
        AuctionDTO dto = DtoMapper.toDto(auctionService.getAuction(req.auctionId));
        send(Message.reply(msg.getRequestId(), MessageType.AUCTION_DETAIL_RESPONSE, dto, gson));
    }

    /** Return the bid history for one auction, sorted by time ascending. */
    private void handleGetBidHistory(Message msg) {
        GetBidHistoryRequest req = msg.parsePayload(gson, GetBidHistoryRequest.class);
        List<BidDTO> bids = bidService.getBidHistory(req.auctionId).stream()
                .map(DtoMapper::toDto)
                .collect(Collectors.toList());
        send(Message.reply(msg.getRequestId(), MessageType.BID_HISTORY_RESPONSE,
                new BidHistoryResponse(req.auctionId, bids), gson));
    }

    /** Return all auctions owned by the logged-in Seller (for the Seller Dashboard). */
    private void handleGetSellerAuctions(Message msg) {
        requireAuth();
        List<AuctionDTO> dtos = auctionService.getSellerAuctions(currentUser.getId()).stream()
                .map(DtoMapper::toDto).collect(Collectors.toList());
        send(Message.reply(msg.getRequestId(), MessageType.SELLER_AUCTIONS_RESPONSE,
                new AuctionsResponse(dtos), gson));
    }

    /** Return all items created by the logged-in Seller (for the Create Auction ComboBox). */
    private void handleGetSellerItems(Message msg) {
        requireAuth();
        List<com.auction.common.dto.ItemDTO> dtos =
                itemService.getItemsBySeller(currentUser.getId()).stream()
                .map(DtoMapper::toDto).collect(Collectors.toList());
        send(Message.reply(msg.getRequestId(), MessageType.SELLER_ITEMS_RESPONSE,
                new ItemsResponse(dtos), gson));
    }

    // ── Bidding handlers ──────────────────────────────────────────────────────

    /**
     * Place a manual bid.
     * BidService acquires the per-auction lock, validates, persists, runs auto-bids,
     * anti-snipes, and broadcasts — this method just translates the request/response.
     */
    private void handlePlaceBid(Message msg) {
        requireAuth();
        PlaceBidRequest req = msg.parsePayload(gson, PlaceBidRequest.class);
        BidTransaction bid = bidService.placeBid(req.auctionId, req.amount, currentUser);
        Auction auction = auctionService.getAuction(req.auctionId);
        send(Message.reply(msg.getRequestId(), MessageType.BID_RESPONSE,
                new BidResponse(DtoMapper.toDto(bid), DtoMapper.toDto(auction)), gson));
    }

    /** Register or update an auto-bid configuration for the logged-in Bidder. */
    private void handleSetAutoBid(Message msg) {
        requireAuth();
        SetAutoBidRequest req = msg.parsePayload(gson, SetAutoBidRequest.class);
        AutoBid ab = bidService.setAutoBid(req.auctionId, req.maxBid, req.increment, currentUser);
        send(Message.reply(msg.getRequestId(), MessageType.AUTO_BID_RESPONSE,
                new AutoBidResponse(ab.getAuctionId(), ab.getMaxBid(), ab.getIncrement()), gson));
    }

    // ── Item / Auction management ─────────────────────────────────────────────

    /** Create a new item for the logged-in Seller; reply with the created ItemDTO. */
    private void handleCreateItem(Message msg) {
        requireAuth();
        CreateItemRequest req = msg.parsePayload(gson, CreateItemRequest.class);
        Item item = itemService.createItem(req.name, req.description,
                req.category, req.extraData, req.imageUrl, currentUser);
        send(Message.reply(msg.getRequestId(), MessageType.ITEM_CREATED,
                DtoMapper.toDto(item), gson));
    }

    /**
     * Create a new auction for an existing item.
     * Parses startTime/endTime from ISO-8601 strings (e.g. "2026-04-22T20:00:00").
     * AuctionService schedules the close task automatically.
     */
    private void handleCreateAuction(Message msg) {
        requireAuth();
        CreateAuctionRequest req = msg.parsePayload(gson, CreateAuctionRequest.class);
        Item item = itemService.getItem(req.itemId);
        Auction auction = auctionService.createAuction(
                item,
                req.startingPrice,
                LocalDateTime.parse(req.startTime, FMT),  // String → LocalDateTime
                LocalDateTime.parse(req.endTime,   FMT),
                currentUser);
        send(Message.reply(msg.getRequestId(), MessageType.AUCTION_CREATED,
                DtoMapper.toDto(auction), gson));
    }

    /** Cancel an auction (seller or admin only); reply with acknowledgment. */
    private void handleCancelAuction(Message msg) {
        requireAuth();
        CancelAuctionRequest req = msg.parsePayload(gson, CancelAuctionRequest.class);
        auctionService.cancelAuction(req.auctionId, currentUser);
        send(Message.reply(msg.getRequestId(), MessageType.AUCTION_CANCELED, "OK", gson));
    }

    // ── Watch / Unwatch ───────────────────────────────────────────────────────

    /**
     * Register this ClientHandler as an AuctionObserver for the given auction.
     * From this point, onBidPlaced/onAuctionEnded/onAuctionExtended will fire
     * whenever something changes in that auction.
     */
    private void handleWatchAuction(Message msg) {
        WatchAuctionRequest req = msg.parsePayload(gson, WatchAuctionRequest.class);
        eventBus.subscribe(req.auctionId, this); // 'this' = this ClientHandler
        send(Message.reply(msg.getRequestId(), MessageType.WATCH_AUCTION, "OK", gson));
    }

    /** Unregister this handler from the auction's watcher set. */
    private void handleUnwatchAuction(Message msg) {
        WatchAuctionRequest req = msg.parsePayload(gson, WatchAuctionRequest.class);
        eventBus.unsubscribe(req.auctionId, this);
        send(Message.reply(msg.getRequestId(), MessageType.UNWATCH_AUCTION, "OK", gson));
    }

    // ── Admin handlers ────────────────────────────────────────────────────────

    /** Return the full user list (admin only). */
    private void handleGetUsers(Message msg) {
        requireAuth();
        requireAdmin();
        List<com.auction.common.dto.UserDTO> dtos = userService.getAllUsers().stream()
                .map(DtoMapper::toDto).collect(Collectors.toList());
        send(Message.reply(msg.getRequestId(), MessageType.USERS_RESPONSE,
                new UsersResponse(dtos), gson));
    }

    /** Ban a user account (admin only). */
    private void handleBanUser(Message msg) {
        requireAuth();
        requireAdmin();
        BanUserRequest req = msg.parsePayload(gson, BanUserRequest.class);
        userService.banUser(req.userId, currentUser);
        send(Message.reply(msg.getRequestId(), MessageType.USER_BANNED, "OK", gson));
    }

    // ── AuctionObserver callbacks (called from AuctionEventBus notify-pool) ───

    /**
     * Called by AuctionEventBus when a new bid is placed in a watched auction.
     * Serialises the updated auction + bid into a BID_BROADCAST message and
     * sends it to this client.  The client's reader thread routes it to
     * BroadcastListener.onBidBroadcast() because the requestId has no pending future.
     */
    @Override
    public void onBidPlaced(Auction auction, BidTransaction bid) {
        sendBroadcast(MessageType.BID_BROADCAST,
                new BidResponse(DtoMapper.toDto(bid), DtoMapper.toDto(auction)));
    }

    /** Called when the watched auction reaches its FINISHED (or CANCELED) state. */
    @Override
    public void onAuctionEnded(Auction auction) {
        sendBroadcast(MessageType.AUCTION_END_BROADCAST, DtoMapper.toDto(auction));
    }

    /** Called when anti-sniping extends the watched auction's end time. */
    @Override
    public void onAuctionExtended(Auction auction) {
        sendBroadcast(MessageType.AUCTION_EXTENDED,
                new AuctionExtendedNotice(auction.getId(),
                        auction.getEndTime().toString()));
    }

    // ── Wire helpers ──────────────────────────────────────────────────────────

    /**
     * Serialise a Message to JSON and write it as one newline-terminated line.
     * Synchronized: prevents interleaving if two threads write at the same time
     * (e.g. a response from the reader thread overlapping with a broadcast from
     * the notify-pool thread).
     */
    private synchronized void send(Message msg) {
        if (out != null) out.println(gson.toJson(msg));
    }

    /** Convenience: build a broadcast (fresh UUID) and send it. */
    private void sendBroadcast(MessageType type, Object payload) {
        send(Message.broadcast(type, payload, gson));
    }

    /**
     * Build and send an ERROR response.
     * If requestId is null (malformed message — we have no id to echo),
     * we send a broadcast-style error with a fresh UUID.
     */
    private void sendError(String requestId, String message) {
        Message err = requestId != null
                ? Message.reply(requestId, MessageType.ERROR,
                        new ErrorResponse(message), gson)
                : Message.broadcast(MessageType.ERROR,
                        new ErrorResponse(message), gson);
        send(err);
    }

    /** Throw AuthException if the client hasn't logged in yet. */
    private void requireAuth() {
        if (currentUser == null) throw new AuthException("Not authenticated");
    }

    /** Throw AuthException if the logged-in user is not an Admin. */
    private void requireAdmin() {
        if (currentUser.getRole() != UserRole.ADMIN)
            throw new AuthException("Admin access required");
    }
}
