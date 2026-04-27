package com.auction.common.dto;

/**
 * FILE ROLE: Wire representation of an auction item.
 *
 * Items are created by Sellers before an auction is opened.
 * An item can only belong to one seller, but a single item could theoretically
 * be re-auctioned (though the current UI doesn't support re-listing).
 *
 * The 'extraData' field carries category-specific JSON so we don't need
 * separate wire types for Electronics, Art, and Vehicle.  The client can
 * parse it if it wants to display brand, year, artist, etc.
 *
 * USED BY:
 *   - Server: DtoMapper.toDto(Item) produces this from a domain Item.
 *   - AuctionDTO: embeds an ItemDTO so the client never needs a second round-trip
 *     to find out what item is being auctioned.
 *   - SellerDashboardController: lists ItemDTOs in the ComboBox for auction creation.
 */
public class ItemDTO {

    private long id;          // database primary key
    private String name;       // short title shown in auction list
    private String description; // longer text shown in auction detail screen
    private String category;   // "ELECTRONICS", "ART", or "VEHICLE"
    private long sellerId;     // the User.id of the Seller who created this item
    private String sellerName; // denormalised for display; avoids a join on the client side
    private String imageUrl;   // optional image URL or local file path
    private String extraData;  // optional JSON blob for category-specific fields
    private String createdAt;  // ISO-8601 timestamp when the item was persisted

    public ItemDTO() {} // required by Gson

    // ── Getters ───────────────────────────────────────────────────────────────

    /** Database primary key — used when creating an auction to reference the item. */
    public long getId() { return id; }

    /** Short title displayed in auction list and detail screens. */
    public String getName() { return name; }

    /** Longer description shown in the auction detail left panel. */
    public String getDescription() { return description; }

    /**
     * Category string matching ItemCategory enum names.
     * The client can use this to display an appropriate icon or label.
     */
    public String getCategory() { return category; }

    /** The seller's User.id — used to verify ownership for cancellation. */
    public long getSellerId() { return sellerId; }

    /** The seller's username — displayed directly without a lookup. */
    public String getSellerName() { return sellerName; }

    /** Optional image URL or local file path shown on the auction detail screen. */
    public String getImageUrl() { return imageUrl; }

    /**
     * Optional free-form JSON data for category-specific attributes.
     * Example for ELECTRONICS: {"brand":"Sony","model":"WH-1000XM5","warranty":"2yr"}
     * The server stores it as a TEXT column; the client may display or ignore it.
     */
    public String getExtraData() { return extraData; }

    /** ISO-8601 creation timestamp. */
    public String getCreatedAt() { return createdAt; }

    // ── Setters (needed by Gson) ───────────────────────────────────────────────
    public void setId(long id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setCategory(String category) { this.category = category; }
    public void setSellerId(long sellerId) { this.sellerId = sellerId; }
    public void setSellerName(String sellerName) { this.sellerName = sellerName; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public void setExtraData(String extraData) { this.extraData = extraData; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
