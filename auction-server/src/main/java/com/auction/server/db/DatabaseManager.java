package com.auction.server.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Singleton that wraps one JDBC {@link Connection} to the SQLite file {@code auction.db}.
 *
 * <p><b>Singleton pattern:</b> {@link #getInstance()} uses double-checked locking
 * ({@code volatile} field + synchronized block) so only one connection bundle is created
 * even if many threads call it on startup.</p>
 *
 * <p><b>Who talks to it:</b> Every {@code SQLite*DAO} class calls {@link #getConnection()}
 * to run SQL. If the database file is missing, SQLite creates it; {@link #initSchema()}
 * runs {@code CREATE TABLE IF NOT EXISTS} so first boot is automatic.</p>
 *
 * <p><b>Testing hook:</b> {@link #resetForTesting()} closes the connection so tests can
 * reopen with a different {@code auction.db.url} system property.</p>
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
            // JDBC drivers are the bridge between Java and a database engine.
            // This line makes sure the SQLite bridge class is loaded before Java asks for a connection.
            Class.forName("org.sqlite.JDBC");
            String url = resolveUrl();
            // DriverManager opens the database connection. For SQLite, the URL points to a file,
            // so there is no separate database server process running in the background.
            connection = DriverManager.getConnection(url);
            // PRAGMA is a SQLite setting command. This one tells SQLite to enforce relationships
            // like "an auction must point at an existing item" instead of silently accepting bad rows.
            connection.createStatement().execute("PRAGMA foreign_keys=ON");
            initSchema();
            log.info("Database initialised at {}", url);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialise database", e);
        }
    }

    public static DatabaseManager getInstance() {
        if (instance == null) {
            // synchronized means only one thread at a time can enter this block.
            // Without it, two client threads could both try to open and initialize the database at once.
            synchronized (DatabaseManager.class) {
                if (instance == null) {
                    instance = new DatabaseManager();
                }
            }
        }
        return instance;
    }

    public static synchronized void resetForTesting() {
        if (instance != null) {
            try {
                if (instance.connection != null && !instance.connection.isClosed()) {
                    // Closing the JDBC connection releases the SQLite file handle.
                    // Tests need this before they can delete the temporary database file on Windows.
                    instance.connection.close();
                }
            } catch (SQLException ignored) {}
            instance = null;
        }
    }

    public synchronized Connection getConnection() {
        try {
            if (connection == null || connection.isClosed()) {
                // If the old JDBC connection was closed, create a new one to the same SQLite file.
                connection = DriverManager.getConnection(resolveUrl());
                // SQLite settings live on the connection, so a reopened connection must set this again.
                connection.createStatement().execute("PRAGMA foreign_keys=ON");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to reconnect to database", e);
        }
        return connection;
    }

    private void initSchema() throws SQLException {
        // Statement sends SQL text to SQLite. try-with-resources closes it automatically
        // even if one of the CREATE TABLE commands fails.
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
                    image_url   TEXT,
                    extra_data  TEXT,
                    created_at  TEXT NOT NULL
                )""");

            ensureColumnExists("items", "image_url TEXT");

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

    private void ensureColumnExists(String table, String columnDefinition) throws SQLException {
        try (Statement alter = connection.createStatement()) {
            alter.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + columnDefinition);
        } catch (SQLException e) {
            String message = e.getMessage();
            if (message == null || !message.toLowerCase().contains("duplicate column name")) {
                throw e;
            }
        }
    }
}
