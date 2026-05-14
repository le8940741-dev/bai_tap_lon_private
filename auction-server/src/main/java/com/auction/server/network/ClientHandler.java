package com.auction.server.network;

import com.auction.common.dto.AuctionDTO;
import com.auction.common.dto.BidDTO;
import com.auction.common.dto.ItemDTO;
import com.auction.common.dto.UserDTO;
import com.auction.common.protocol.Message;
import com.auction.common.protocol.MessageType;
import com.auction.common.request.EmptyPayload;
import com.auction.common.request.Requests.*;
import com.auction.common.request.Responses.*;
import com.auction.server.exception.AuctionException;
import com.auction.server.exception.AuthException;
import com.auction.server.exception.BidException;
import com.auction.server.model.Auction;
import com.auction.server.model.AutoBid;
import com.auction.server.model.BidTransaction;
import com.auction.server.model.Item;
import com.auction.server.model.User;
import com.auction.server.model.UserRole;
import com.auction.server.observer.AuctionEventBus;
import com.auction.server.observer.AuctionObserver;
import com.auction.server.service.AuctionService;
import com.auction.server.service.BidService;
import com.auction.server.service.ItemService;
import com.auction.server.service.UserService;
import com.auction.server.util.DtoMapper;
import com.google.gson.Gson;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Bridges one TCP socket to the rest of the server: JSON in, business logic, JSON out.
 *
 * <p><b>When the program runs:</b> {@link AuctionServer} calls {@code new ClientHandler(...)}
 * for each accepted connection and submits this object to a thread pool. {@link #run()}
 * blocks in a loop on {@code readLine()}. Each line is one {@link Message}. {@link #dispatch(Message)}
 * switches on {@link MessageType} and forwards to {@link UserService}, {@link ItemService},
 * {@link AuctionService}, or {@link BidService}. Successful work is wrapped in DTOs via
 * {@link DtoMapper} and sent with {@link Message#reply} so the client can match the response
 * to its pending request.</p>
 *
 * <p><b>Observer pattern:</b> This class implements {@link AuctionObserver}. When users
 * {@code WATCH_AUCTION}, {@link AuctionEventBus#subscribe(long, AuctionObserver)} stores
 * <i>this handler</i> in a set. Later, {@link BidService} (or the scheduler) publishes events;
 * the bus invokes {@link #onBidPlaced}, {@link #onAuctionEnded}, or {@link #onAuctionExtended},
 * which sends unsolicited {@link Message#broadcast} lines to this socket so the JavaFX client
 * can update without polling.</p>
 *
 * <p><b>OOP you should notice:</b> {@code currentUser} is typed as abstract {@link User}
 * (polymorphism). Role checks use {@link User#getRole()} / {@link UserRole}. Exceptions
 * {@link AuthException}, {@link BidException}, {@link AuctionException} translate to
 * {@link MessageType#ERROR} payloads instead of crashing the connection.</p>
 */
public final class ClientHandler implements Runnable, AuctionObserver {

    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // Socket: one end of the TCP connection; this handler owns one socket for one connected client.
    private final Socket socket;
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

    // Main I/O loop

    @Override
    public void run() {
        try (
            // getInputStream() reads raw bytes from this client's socket.
            // InputStreamReader turns those bytes into characters, and BufferedReader lets us read one line at a time.
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
            // getOutputStream() writes bytes back to the same socket.
            // PrintWriter.println(...) sends one complete JSON message line to the client.
            PrintWriter out = new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true)
            // 'true' = auto-flush: every println() immediately sends the line
        ) {
            this.out = out;
            String line;
            // readLine() blocks. That means this thread sleeps here until the client sends
            // a newline-terminated message or disconnects.
            while ((line = in.readLine()) != null) {
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

    // Message dispatcher

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
            // Expected business rule violations - send a clean error message to the client.
            sendError(msg.getRequestId(), e.getMessage());
        } catch (Exception e) {
            // Unexpected errors - log the full stack trace server-side, send generic message.
            log.error("Unhandled error processing {}", msg.getType(), e);
            sendError(msg.getRequestId(), "Internal server error");
        }
    }

    // Auth handlers

    private void handleRegister(Message msg) {
        RegisterRequest req = msg.parsePayload(gson, RegisterRequest.class);
        User user = userService.register(req.username, req.password, req.email, req.role);
        reply(msg, MessageType.REGISTER_RESPONSE, DtoMapper.toDto(user));
    }

    private void handleLogin(Message msg) {
        LoginRequest req = msg.parsePayload(gson, LoginRequest.class);
        currentUser = userService.login(req.username, req.password);
        reply(msg, MessageType.LOGIN_RESPONSE, DtoMapper.toDto(currentUser));
    }

    private void handleLogout(Message msg) {
        eventBus.unsubscribeAll(this);
        currentUser = null;
        replyEmpty(msg, MessageType.LOGOUT);
    }

    // Auction query handlers

    private void handleGetAuctions(Message msg) {
        reply(msg, MessageType.AUCTIONS_RESPONSE,
                new AuctionsResponse(toAuctionDtos(auctionService.getAllAuctions())));
    }

    private void handleGetAuctionDetail(Message msg) {
        GetAuctionDetailRequest req = msg.parsePayload(gson, GetAuctionDetailRequest.class);
        AuctionDTO dto = DtoMapper.toDto(auctionService.getAuction(req.auctionId));
        reply(msg, MessageType.AUCTION_DETAIL_RESPONSE, dto);
    }

    private void handleGetBidHistory(Message msg) {
        GetBidHistoryRequest req = msg.parsePayload(gson, GetBidHistoryRequest.class);
        List<BidDTO> bids = bidService.getBidHistory(req.auctionId).stream()
                .map(DtoMapper::toDto)
                .toList();
        reply(msg, MessageType.BID_HISTORY_RESPONSE,
                new BidHistoryResponse(req.auctionId, bids));
    }

    private void handleGetSellerAuctions(Message msg) {
        requireAuth();
        reply(msg, MessageType.SELLER_AUCTIONS_RESPONSE,
                new AuctionsResponse(toAuctionDtos(
                        auctionService.getSellerAuctions(currentUser.getId()))));
    }

    private void handleGetSellerItems(Message msg) {
        requireAuth();
        reply(msg, MessageType.SELLER_ITEMS_RESPONSE,
                new ItemsResponse(toItemDtos(
                        itemService.getItemsBySeller(currentUser.getId()))));
    }

    // Bidding handlers

    private void handlePlaceBid(Message msg) {
        requireAuth();
        PlaceBidRequest req = msg.parsePayload(gson, PlaceBidRequest.class);
        BidTransaction bid = bidService.placeBid(req.auctionId, req.amount, currentUser);
        Auction auction = auctionService.getAuction(req.auctionId);
        reply(msg, MessageType.BID_RESPONSE,
                new BidResponse(DtoMapper.toDto(bid), DtoMapper.toDto(auction)));
    }

    private void handleSetAutoBid(Message msg) {
        requireAuth();
        SetAutoBidRequest req = msg.parsePayload(gson, SetAutoBidRequest.class);
        AutoBid ab = bidService.setAutoBid(req.auctionId, req.maxBid, req.increment, currentUser);
        reply(msg, MessageType.AUTO_BID_RESPONSE,
                new AutoBidResponse(ab.getAuctionId(), ab.getMaxBid(), ab.getIncrement()));
    }

    // Item / Auction management

    private void handleCreateItem(Message msg) {
        requireAuth();
        CreateItemRequest req = msg.parsePayload(gson, CreateItemRequest.class);
        Item item = itemService.createItem(req.name, req.description,
                req.category, req.extraData, req.imageUrl, currentUser);
        reply(msg, MessageType.ITEM_CREATED, DtoMapper.toDto(item));
    }

    private void handleCreateAuction(Message msg) {
        requireAuth();
        CreateAuctionRequest req = msg.parsePayload(gson, CreateAuctionRequest.class);
        Item item = itemService.getItem(req.itemId);
        Auction auction = auctionService.createAuction(
                item,
                req.startingPrice,
                LocalDateTime.parse(req.startTime, FMT),  // String -> LocalDateTime
                LocalDateTime.parse(req.endTime,   FMT),
                currentUser);
        reply(msg, MessageType.AUCTION_CREATED, DtoMapper.toDto(auction));
    }

    private void handleCancelAuction(Message msg) {
        requireAuth();
        CancelAuctionRequest req = msg.parsePayload(gson, CancelAuctionRequest.class);
        auctionService.cancelAuction(req.auctionId, currentUser);
        replyEmpty(msg, MessageType.AUCTION_CANCELED);
    }

    // Watch / Unwatch

    private void handleWatchAuction(Message msg) {
        WatchAuctionRequest req = msg.parsePayload(gson, WatchAuctionRequest.class);
        eventBus.subscribe(req.auctionId, this); // 'this' = this ClientHandler
        replyEmpty(msg, MessageType.WATCH_AUCTION);
    }

    private void handleUnwatchAuction(Message msg) {
        WatchAuctionRequest req = msg.parsePayload(gson, WatchAuctionRequest.class);
        eventBus.unsubscribe(req.auctionId, this);
        replyEmpty(msg, MessageType.UNWATCH_AUCTION);
    }

    // Admin handlers

    private void handleGetUsers(Message msg) {
        requireAuth();
        requireAdmin();
        reply(msg, MessageType.USERS_RESPONSE,
                new UsersResponse(toUserDtos(userService.getAllUsers())));
    }

    private void handleBanUser(Message msg) {
        requireAuth();
        requireAdmin();
        BanUserRequest req = msg.parsePayload(gson, BanUserRequest.class);
        userService.banUser(req.userId, currentUser);
        replyEmpty(msg, MessageType.USER_BANNED);
    }

    // AuctionObserver callbacks (called from AuctionEventBus notify-pool)

    @Override
    public void onBidPlaced(Auction auction, BidTransaction bid) {
        sendBroadcast(MessageType.BID_BROADCAST,
                new BidResponse(DtoMapper.toDto(bid), DtoMapper.toDto(auction)));
    }

    @Override
    public void onAuctionEnded(Auction auction) {
        sendBroadcast(MessageType.AUCTION_END_BROADCAST, DtoMapper.toDto(auction));
    }

    @Override
    public void onAuctionExtended(Auction auction) {
        sendBroadcast(MessageType.AUCTION_EXTENDED,
                new AuctionExtendedNotice(auction.getId(),
                        auction.getEndTime().toString()));
    }

    // DTO helpers

    private List<AuctionDTO> toAuctionDtos(List<Auction> auctions) {
        return auctions.stream().map(DtoMapper::toDto).toList();
    }

    private List<ItemDTO> toItemDtos(List<Item> items) {
        return items.stream().map(DtoMapper::toDto).toList();
    }

    private List<UserDTO> toUserDtos(List<User> users) {
        return users.stream().map(DtoMapper::toDto).toList();
    }

    // Wire helpers

    private void reply(Message request, MessageType type, Object payload) {
        send(Message.reply(request.getRequestId(), type, payload, gson));
    }

    private void replyEmpty(Message request, MessageType type) {
        reply(request, type, EmptyPayload.INSTANCE);
    }

    // synchronized protects the socket writer. Broadcasts and normal replies can come from
    // different server threads, and two println calls must not overlap on the same socket.
    private synchronized void send(Message msg) {
        if (out != null) out.println(gson.toJson(msg));
    }

    private void sendBroadcast(MessageType type, Object payload) {
        send(Message.broadcast(type, payload, gson));
    }

    private void sendError(String requestId, String message) {
        Message err = requestId != null
                ? Message.reply(requestId, MessageType.ERROR,
                        new ErrorResponse(message), gson)
                : Message.broadcast(MessageType.ERROR,
                        new ErrorResponse(message), gson);
        send(err);
    }

    private void requireAuth() {
        if (currentUser == null) throw new AuthException("Not authenticated");
    }

    private void requireAdmin() {
        if (currentUser.getRole() != UserRole.ADMIN)
            throw new AuthException("Admin access required");
    }
}
