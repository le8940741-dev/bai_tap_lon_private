package com.auction.server.model;

import java.time.LocalDateTime; // Java's date-time type without timezone — all times stored as local server time

/**
 * FILE ROLE: The abstract root of the entire domain model hierarchy.
 *
 * Every persistent object in the system (User, Item, Auction, BidTransaction,
 * AutoBid) extends Entity.  This satisfies the OOP requirement for a shared
 * abstract base class, and gives us two things for free across all subclasses:
 *
 *   1. 'id'        — the database primary key (set by the DAO after INSERT).
 *   2. 'createdAt' — auto-set to now() at construction time.
 *
 * DESIGN DECISION — why abstract + printInfo():
 *   The assignment spec requires polymorphism demonstrated via overridden methods.
 *   printInfo() is the designated hook: every subclass prints its own fields.
 *   You can call entity.printInfo() on any object in the hierarchy without
 *   knowing its concrete type — Java dispatch calls the right override.
 *
 * INHERITANCE TREE:
 *   Entity
 *   ├── User (abstract)
 *   │   ├── Bidder
 *   │   ├── Seller
 *   │   └── Admin
 *   ├── Item (abstract)
 *   │   ├── Electronics
 *   │   ├── Art
 *   │   └── Vehicle
 *   ├── Auction
 *   ├── BidTransaction
 *   └── AutoBid
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

    // ── Abstract contract ──────────────────────────────────────────────────────

    /**
     * Prints a human-readable summary of this object to stdout.
     * Every concrete subclass must override this to demonstrate polymorphism.
     *
     * Example output from Bidder.printInfo():
     *   [BIDDER] id=3  username=alice               email=alice@x.com  active=true
     */
    public abstract void printInfo();

    // ── Getters / setters ──────────────────────────────────────────────────────

    /** The database primary key; 0 until persisted by a DAO. */
    public long getId() { return id; }

    /** Called by the DAO immediately after INSERT to store the generated key. */
    public void setId(long id) { this.id = id; }

    /** The instant this object was instantiated (persisted as created_at). */
    public LocalDateTime getCreatedAt() { return createdAt; }

    /** Called by DAOs when reading existing rows from the database. */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
