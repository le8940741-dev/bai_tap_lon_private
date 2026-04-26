package com.auction.server.model;

/**
 * FILE ROLE: Abstract domain model for auction items.
 *
 * Extends Entity (gets id + createdAt).
 * Subclassed by Electronics, Art, and Vehicle.
 *
 * WHY ABSTRACT:
 *   Different categories have different relevant attributes
 *   (e.g. Electronics has brand/warranty, Art has artist/medium, Vehicle has mileage/year).
 *   We store those extra attributes as a JSON blob in 'extraData' rather than adding
 *   columns for every possible field — this keeps the schema simple while still
 *   allowing category-specific data.
 *
 *   The abstract getCategory() forces every subclass to declare its category,
 *   which the DAO uses when reading from the database to pick the right constructor
 *   via ItemFactory.create(category).
 *
 * RELATIONSHIP TO AUCTION:
 *   One Item can be referenced by one Auction.
 *   The Auction embeds the Item directly (not just an itemId) so the detail
 *   screen never needs a separate item-lookup request.
 */
public abstract class Item extends Entity {

    private String name;       // short display title (e.g. "Sony WH-1000XM5")
    private String description; // longer free-text description
    private long sellerId;     // the Seller who created this item
    private String sellerName; // denormalised for display
    /** Free-form JSON for category-specific fields, stored as TEXT in SQLite. */
    private String extraData;

    protected Item() { super(); }

    // ── Abstract category declaration ──────────────────────────────────────────

    /**
     * Returns the enum constant for this item's category.
     * Used by SQLiteItemDAO and SQLiteAuctionDAO when reading rows to decide
     * which concrete Item subclass to instantiate via ItemFactory.
     */
    public abstract ItemCategory getCategory();

    // ── Polymorphic display ────────────────────────────────────────────────────

    @Override
    public void printInfo() {
        // getCategory() resolves at runtime to Electronics/Art/Vehicle — polymorphism.
        System.out.printf("[%s] id=%d  name=%-30s  seller=%s%n",
                getCategory(), id, name, sellerName);
    }

    // ── Getters / setters ──────────────────────────────────────────────────────

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public long getSellerId() { return sellerId; }
    public void setSellerId(long sellerId) { this.sellerId = sellerId; }

    public String getSellerName() { return sellerName; }
    public void setSellerName(String sellerName) { this.sellerName = sellerName; }

    /**
     * JSON blob for category-specific fields.
     * The server stores it verbatim; neither the server nor the client parse it
     * in the current implementation, but the client can display it as raw info.
     */
    public String getExtraData() { return extraData; }
    public void setExtraData(String extraData) { this.extraData = extraData; }
}
