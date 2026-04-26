package com.auction.server.network;

// ── DAO implementations (concrete classes wired here) ─────────────────────────
import com.auction.server.dao.impl.*;  // SQLiteUserDAO, SQLiteItemDAO, etc.

// ── Services (one shared instance per server process) ─────────────────────────
import com.auction.server.service.*;  // UserService, ItemService, AuctionService, BidService

// ── JSON ──────────────────────────────────────────────────────────────────────
import com.google.gson.Gson;
import com.google.gson.GsonBuilder; // fluent builder for configuring Gson

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// ── Java networking and concurrency ───────────────────────────────────────────
import java.io.IOException;
import java.net.ServerSocket; // listens for incoming TCP connections
import java.net.Socket;       // one connected client socket per accepted connection
import java.util.concurrent.ExecutorService; // thread pool that runs ClientHandlers
import java.util.concurrent.Executors;       // factory for thread pool implementations

/**
 * FILE ROLE: TCP server that accepts connections and wires the whole application together.
 *
 * This class does two things:
 *   1. DEPENDENCY WIRING (Manual DI):
 *      Creates all DAOs, services, and the shared Gson instance.
 *      Injects them into each ClientHandler via constructor arguments.
 *      No DI framework (Spring, Guice) is used — at this scale, manual wiring
 *      in one place is simpler and more transparent.
 *
 *   2. ACCEPT LOOP:
 *      Blocks on serverSocket.accept() in a loop.
 *      Each accepted connection gets its own ClientHandler submitted to the thread pool.
 *      The thread pool (Executors.newCachedThreadPool) creates a new thread per
 *      client, reusing idle threads when clients disconnect.
 *
 * SHARED VS. PER-CLIENT STATE:
 *   Services (UserService, AuctionService, BidService, ItemService) are shared
 *   across all ClientHandlers — they are stateless except for the scheduler and
 *   lock maps in AuctionService/BidService, which are thread-safe.
 *
 *   Gson is also shared — Gson instances are thread-safe after construction.
 *
 *   Each ClientHandler has its own: Socket, PrintWriter, and currentUser field.
 *
 * CALLED BY: ServerMain
 */
public final class AuctionServer {

    private static final Logger log = LoggerFactory.getLogger(AuctionServer.class);

    private final int port; // TCP port to listen on (default 9090, from ServerMain)

    // Shared Gson instance — thread-safe; reused across all ClientHandlers.
    private final Gson gson;

    // ── Shared services (stateless business logic + thread-safe state) ─────────
    private final UserService    userService;
    private final ItemService    itemService;
    private final AuctionService auctionService;
    private final BidService     bidService;

    // Cached thread pool: grows on demand; reuses idle threads.
    // Daemon threads: won't prevent JVM exit when the server process is killed.
    private final ExecutorService clientPool =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                return t;
            });

    /**
     * Construct the server: instantiate all DAOs and services.
     *
     * WHY MANUAL DI:
     *   The dependency graph is shallow (3 layers: DAO → Service → Handler).
     *   A DI framework would add complexity (annotations, classpath scanning,
     *   startup time) for no real benefit at this scale.
     *   Every dependency is explicit and traceable in this constructor.
     *
     * @param port the TCP port to listen on
     */
    public AuctionServer(int port) {
        this.port = port;
        // serializeNulls(): ensures null fields (e.g. winnerId before any bids)
        // are included in JSON as "null" rather than being omitted entirely.
        // Omitting them would cause NullPointerException in the client when it
        // tries to read a field that isn't present.
        this.gson = new GsonBuilder().serializeNulls().create();

        // ── Instantiate DAOs (bottom layer — talks to SQLite) ──────────────────
        SQLiteUserDAO    userDAO    = new SQLiteUserDAO();
        SQLiteItemDAO    itemDAO    = new SQLiteItemDAO();
        SQLiteAuctionDAO auctionDAO = new SQLiteAuctionDAO();
        SQLiteBidDAO     bidDAO     = new SQLiteBidDAO();
        SQLiteAutoBidDAO autoBidDAO = new SQLiteAutoBidDAO();

        // ── Instantiate services (middle layer — business logic) ───────────────
        // AuctionService must be created before BidService because BidService
        // calls auctionService.markRunning() and auctionService.applyAntiSnipe().
        userService    = new UserService(userDAO);
        itemService    = new ItemService(itemDAO);
        auctionService = new AuctionService(auctionDAO); // also restores scheduler on startup
        bidService     = new BidService(auctionDAO, bidDAO, autoBidDAO, auctionService);
    }

    /**
     * Start the server: open the ServerSocket and enter the accept loop.
     *
     * ServerSocket(port) binds to all network interfaces on the given port.
     * The try-with-resources closes the ServerSocket on exit, which causes
     * any blocked accept() call to throw IOException and break the loop cleanly.
     *
     * @throws IOException if the port is already in use or the socket can't be created
     */
    public void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            log.info("Auction server listening on port {}", port);
            // Loop until the thread is interrupted (e.g. CTRL+C sends SIGINT).
            while (!Thread.currentThread().isInterrupted()) {
                Socket client = serverSocket.accept(); // blocks until a client connects
                log.info("Client connected: {}", client.getRemoteSocketAddress());
                // Create a new ClientHandler for this connection and submit it to the pool.
                // The pool thread runs ClientHandler.run() which blocks on readLine().
                clientPool.submit(new ClientHandler(
                        client, gson,
                        userService, itemService, auctionService, bidService));
            }
        }
    }
}
