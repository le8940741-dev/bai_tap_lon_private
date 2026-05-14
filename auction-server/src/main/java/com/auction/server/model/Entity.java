package com.auction.server.model;

import java.time.LocalDateTime; // Java's date-time type without timezone - all times stored as local server time

/**
 * Small inheritance root for “things stored in the database”.
 *
 * <p><b>Shared state:</b> every row gets an {@code id} (filled in after insert) and a
 * {@code createdAt} timestamp for auditing.</p>
 *
 * <p><b>Polymorphism:</b> {@link #printInfo()} is abstract — each concrete subclass
 * ({@link com.auction.server.model.Bidder}, {@link com.auction.server.model.Electronics}, …)
 * prints its own fields so you can see dynamic dispatch in a debugger or console.</p>
 *
 * <p><b>Major families:</b> {@link User} (bidder / seller / admin), {@link Item}
 * (electronics / art / vehicle), plus {@link Auction}, {@link BidTransaction}, {@link AutoBid}.</p>
 */
public abstract class Entity {

    // The database AUTOINCREMENT primary key.
    // Set to 0 at construction; the DAO overwrites it after INSERT ... RETURNING id.
    protected long id;

    // The wall-clock time this object was first created in memory.
    // Persisted in the database as an ISO-8601 TEXT column.
    protected LocalDateTime createdAt;

    protected Entity() {
        // Every subclass automatically gets a creation timestamp.
        this.createdAt = LocalDateTime.now();
    }

    // Abstract contract

    /**
     * Prints a human-readable summary of this object to stdout.
     * Every concrete subclass must override this to demonstrate polymorphism.
     *
     * Example output from Bidder.printInfo():
     *   [BIDDER] id=3  username=alice               email=alice@x.com  active=true
     */
    public abstract void printInfo();

    // Getters / setters

    /** The database primary key; 0 until persisted by a DAO. */
    public long getId() { return id; }

    /** Called by the DAO immediately after INSERT to store the generated key. */
    public void setId(long id) { this.id = id; }

    /** The instant this object was instantiated (persisted as created_at). */
    public LocalDateTime getCreatedAt() { return createdAt; }

    /** Called by DAOs when reading existing rows from the database. */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
