package com.auction.server;

import com.auction.server.db.DatabaseManager;
import com.auction.server.network.AuctionServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code public static void main} entry point for the server JVM.
 *
 * <p><b>Startup order:</b> (1) {@link DatabaseManager#getInstance()} opens SQLite, creates
 * tables if needed, seeds the default admin user. (2) {@link AuctionServer} is constructed,
 * which builds DAOs and services. (3) {@link AuctionServer#start()} opens the listening socket
 * and never returns until the process is stopped.</p>
 *
 * <p><b>Interaction:</b> Every other server class is reachable from here through that
 * construction chain — there is no global {@code ApplicationContext}; follow {@code new}
 * calls to learn the architecture.</p>
 */
public final class ServerMain {

    private static final Logger log = LoggerFactory.getLogger(ServerMain.class);
    public static final int DEFAULT_PORT = 9090;

    public static void main(String[] args) throws Exception {
        // Accept an optional port argument; fall back to 9090.
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;

        // Force schema creation + admin seeding before accepting connections.
        // If this fails (e.g. no write permission for auction.db), the error
        // is clear and the server exits rather than accepting connections it
        // can't serve.
        DatabaseManager.getInstance();

        log.info("Starting Auction Server on port {}", port);
        // AuctionServer.start() blocks here until the process is killed.
        new AuctionServer(port).start();
    }
}
