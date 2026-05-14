package com.auction.server.network;

import com.auction.server.dao.impl.SQLiteAuctionDAO;
import com.auction.server.dao.impl.SQLiteAutoBidDAO;
import com.auction.server.dao.impl.SQLiteBidDAO;
import com.auction.server.dao.impl.SQLiteItemDAO;
import com.auction.server.dao.impl.SQLiteUserDAO;
import com.auction.server.service.AuctionService;
import com.auction.server.service.BidService;
import com.auction.server.service.ItemService;
import com.auction.server.service.UserService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Listens on a TCP port and hands each new socket to a {@link ClientHandler}.
 *
 * <p><b>Composition (manual wiring):</b> The constructor builds the persistence layer
 * (SQLite DAO implementations), then the service layer on top, then stores those services.
 * This is <i>not</i> Spring — dependencies are visible in one place so a student can trace
 * {@code new BidService(auctionDAO, ...)} without XML or annotations.</p>
 *
 * <p><b>Concurrency:</b> {@link #start()} blocks in {@code accept()}. Each accepted
 * {@link Socket} is processed on its own pool thread running {@link ClientHandler#run()},
 * so many clients can talk at once. DAO methods use their own {@code synchronized} blocks
 * where SQLite needs one writer at a time.</p>
 *
 * <p><b>Gson note:</b> {@code serializeNulls()} keeps JSON fields such as {@code winnerId}
 * as explicit {@code null} so the client DTOs do not break when Gson reads missing keys.</p>
 */
public final class AuctionServer {

    private static final Logger log = LoggerFactory.getLogger(AuctionServer.class);

    private final int port; // TCP port to listen on (default 9090, from ServerMain)

    // Shared Gson instance - thread-safe; reused across all ClientHandlers.
    private final Gson gson;

    // Shared services (stateless business logic + thread-safe state)
    private final UserService    userService;
    private final ItemService    itemService;
    private final AuctionService auctionService;
    private final BidService     bidService;

    // ExecutorService is Java's "give this work to background threads" object.
    // A cached thread pool grows when more clients connect and reuses old threads when clients leave.
    // Daemon threads do not keep the JVM alive by themselves, so stopping the server process can still exit cleanly.
    private final ExecutorService clientPool =
            Executors.newCachedThreadPool(r -> {
                // This factory customizes each worker thread before the pool starts using it.
                Thread t = new Thread(r);
                t.setDaemon(true);
                return t;
            });

    public AuctionServer(int port) {
        this.port = port;
        // serializeNulls(): ensures null fields (e.g. winnerId before any bids)
        // are included in JSON as "null" rather than being omitted entirely.
        // Omitting them would cause NullPointerException in the client when it
        // tries to read a field that isn't present.
        this.gson = new GsonBuilder().serializeNulls().create();

        // Instantiate DAOs (bottom layer - talks to SQLite)
        SQLiteUserDAO    userDAO    = new SQLiteUserDAO();
        SQLiteItemDAO    itemDAO    = new SQLiteItemDAO();
        SQLiteAuctionDAO auctionDAO = new SQLiteAuctionDAO();
        SQLiteBidDAO     bidDAO     = new SQLiteBidDAO();
        SQLiteAutoBidDAO autoBidDAO = new SQLiteAutoBidDAO();

        // Instantiate services (middle layer - business logic)
        // AuctionService must be created before BidService because BidService
        // calls auctionService.markRunning() and auctionService.applyAntiSnipe().
        userService    = new UserService(userDAO);
        itemService    = new ItemService(itemDAO);
        auctionService = new AuctionService(auctionDAO); // also restores scheduler on startup
        bidService     = new BidService(auctionDAO, bidDAO, autoBidDAO, auctionService);
    }

    public void start() throws IOException {
        // ServerSocket is the listening side of TCP. It binds to a port and waits for clients
        // to open their own Socket connections to that port.
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            log.info("Auction server listening on port {}", port);
            // Loop until the thread is interrupted. In plain language, this means
            // "keep accepting clients until the server is told to stop."
            while (!Thread.currentThread().isInterrupted()) {
                // accept() blocks, which means this thread pauses here until a client connects.
                // The returned Socket is the private two-way conversation with that one client.
                Socket client = serverSocket.accept();
                log.info("Client connected: {}", client.getRemoteSocketAddress());
                // submit() gives the client conversation to a pool thread. This keeps the accept loop
                // free to go back and wait for the next client.
                clientPool.submit(new ClientHandler(
                        client, gson,
                        userService, itemService, auctionService, bidService));
            }
        }
    }
}
