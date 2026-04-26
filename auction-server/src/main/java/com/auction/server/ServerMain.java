package com.auction.server;

import com.auction.server.db.DatabaseManager; // singleton that initialises the SQLite schema
import com.auction.server.network.AuctionServer; // the TCP server

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FILE ROLE: Server process entry point.
 *
 * Does three things in order:
 *   1. Parse the port from command-line args (default: 9090).
 *   2. Eagerly initialise DatabaseManager so the schema is created and the
 *      admin account is seeded BEFORE any client can connect.
 *   3. Construct and start AuctionServer, which blocks in the accept loop.
 *
 * HOW TO RUN:
 *   java -jar auction-server-1.0.0-fat.jar          # port 9090
 *   java -jar auction-server-1.0.0-fat.jar 8080     # port 8080
 *
 * DEFAULT ADMIN CREDENTIALS:
 *   username: admin
 *   password: admin
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
