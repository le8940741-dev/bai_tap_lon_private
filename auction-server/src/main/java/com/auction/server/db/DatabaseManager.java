package com.auction.server.db;

/**
 * FILE ROLE: SQLite database lifecycle manager — Singleton pattern.
 *
 * RESPONSIBILITIES:
 *   1. Open (or reopen) the single JDBC Connection to the SQLite database file.
 *   2. Run initSchema() on first connection to create all tables if they do not
 *      exist yet — safe to call on every startup thanks to CREATE TABLE IF NOT EXISTS.
 *   3. Seed the default admin account (username=admin, password=admin) using
 *      INSERT OR IGNORE so the seed only runs once even across restarts.
 *   4. Expose getConnection() to all DAO classes — they call this instead of
 *      opening their own connections, ensuring the whole server shares one connection.
 *
 * WHY ONE SHARED CONNECTION:
 *   SQLite in WAL mode supports concurrent readers + one writer.  In DELETE mode
 *   (which we use — WAL is disabled for Windows compatibility) it allows one
 *   connection at a time.  Sharing one connection serialised through synchronized
 *   DAO methods is the simplest correct design.  For a PostgreSQL backend, this
 *   class would be replaced by a connection pool (e.g. HikariCP) with no changes
 *   to the DAOs, because they depend on the UserDAO/AuctionDAO interfaces only.
 *
 * WHY NO WAL:
 *   PRAGMA journal_mode=WAL requires creating shared-memory sidecar files
 *   (.db-wal, .db-shm) in the same directory as the database.  On Windows,
 *   temp-directory paths sometimes block this file creation, causing an
 *   SQLITE_BUSY error before any schema is written.  The default DELETE
 *   journal mode avoids this with no correctness loss at our scale.
 *
 * TESTABILITY:
 *   The DB URL is read from the system property "auction.db.url" rather than
 *   being hardcoded.  Tests set this property to point at a temp file, then call
 *   resetForTesting() to destroy the singleton before creating a fresh one.
 *   This gives each test class an isolated, clean database.
 *
 * ADMIN SEED HASH:
 *   The stored hash "8c6976e5..." is SHA-256 of the string "admin" with no salt.
 *   PasswordUtil.verify() detects the absence of a ":" separator and handles
 *   this legacy format — so login with username=admin, password=admin works.
 *
 * USED BY: All five SQLite DAO implementations via getConnection().
 */
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Singleton database connection manager for SQLite.
 *
 * <p>WAL journal mode is intentionally omitted: it requires shared-memory
 * file creation that fails on some Windows temp-directory configurations,
 * and the default DELETE mode is sufficient for correctness at this scale.</p>
 *
 * <p>The DB URL is read from the system property {@code auction.db.url} so
 * tests can point at a temporary file without touching production data.
 * {@link #resetForTesting()} drops the singleton so a fresh instance is
 * created on the next {@link #getInstance()} call.</p>
 */
public final class DatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private static volatile DatabaseManager instance;
    private Connection connection;

    private static String resolveUrl() {
        return System.getProperty("auction.db.url", "jdbc:sqlite:auction.db");
    }

    private DatabaseManager() {
        try {
            Class.forName("org.sqlite.JDBC");
            String url = resolveUrl();
            connection = DriverManager.getConnection(url);
            // foreign_keys only — WAL removed (Windows temp-dir incompatibility)
            connection.createStatement().execute("PRAGMA foreign_keys=ON");
            initSchema();
            log.info("Database initialised at {}", url);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialise database", e);
        }
    }

    public static DatabaseManager getInstance() {
        if (instance == null) {
            synchronized (DatabaseManager.class) {
                if (instance == null) {
                    instance = new DatabaseManager();
                }
            }
        }
        return instance;
    }

    /**
     * Destroy the singleton and close the underlying connection.
     * Call this in {@code @AfterAll} test teardown before changing
     * {@code auction.db.url}.
     */
    public static synchronized void resetForTesting() {
        if (instance != null) {
            try {
                if (instance.connection != null && !instance.connection.isClosed()) {
                    instance.connection.close();
                }
            } catch (SQLException ignored) {}
            instance = null;
        }
    }

    public synchronized Connection getConnection() {
        try {
            if (connection == null || connection.isClosed()) {
                connection = DriverManager.getConnection(resolveUrl());
                connection.createStatement().execute("PRAGMA foreign_keys=ON");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to reconnect to database", e);
        }
        return connection;
    }

    private void initSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS users (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    username      TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    email         TEXT NOT NULL UNIQUE,
                    role          TEXT NOT NULL CHECK(role IN ('BIDDER','SELLER','ADMIN')),
                    active        INTEGER NOT NULL DEFAULT 1,
                    created_at    TEXT NOT NULL
                )""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS items (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    name        TEXT NOT NULL,
                    description TEXT,
                    category    TEXT NOT NULL CHECK(category IN ('ELECTRONICS','ART','VEHICLE')),
                    seller_id   INTEGER NOT NULL REFERENCES users(id),
                    extra_data  TEXT,
                    created_at  TEXT NOT NULL
                )""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS auctions (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    item_id          INTEGER NOT NULL REFERENCES items(id),
                    starting_price   REAL    NOT NULL,
                    current_price    REAL    NOT NULL,
                    start_time       TEXT    NOT NULL,
                    end_time         TEXT    NOT NULL,
                    status           TEXT    NOT NULL DEFAULT 'OPEN',
                    seller_id        INTEGER NOT NULL REFERENCES users(id),
                    winner_id        INTEGER REFERENCES users(id),
                    created_at       TEXT    NOT NULL
                )""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS bid_transactions (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    auction_id  INTEGER NOT NULL REFERENCES auctions(id),
                    bidder_id   INTEGER NOT NULL REFERENCES users(id),
                    amount      REAL    NOT NULL,
                    is_auto_bid INTEGER NOT NULL DEFAULT 0,
                    created_at  TEXT    NOT NULL
                )""");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS auto_bids (
                    id             INTEGER PRIMARY KEY AUTOINCREMENT,
                    auction_id     INTEGER NOT NULL REFERENCES auctions(id),
                    bidder_id      INTEGER NOT NULL REFERENCES users(id),
                    max_bid        REAL    NOT NULL,
                    increment      REAL    NOT NULL,
                    registered_at  TEXT    NOT NULL,
                    active         INTEGER NOT NULL DEFAULT 1,
                    UNIQUE(auction_id, bidder_id)
                )""");

            // Seed admin account.
            // Hash is SHA-256("admin") = 8c6976e5b541...
            // Login: username=admin  password=admin
            st.executeUpdate("""
                INSERT OR IGNORE INTO users
                    (username, password_hash, email, role, active, created_at)
                VALUES
                    ('admin',
                     '8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918',
                     'admin@auction.local', 'ADMIN', 1,
                     strftime('%Y-%m-%dT%H:%M:%S', 'now'))""");
        }
    }
}
