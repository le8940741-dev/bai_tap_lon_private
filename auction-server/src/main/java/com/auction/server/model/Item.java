package com.auction.server.model;

/**
 * Abstract item listed in auctions — another inheritance hierarchy parallel to {@link User}.
 *
 * <p>Each subclass supplies {@link #getCategory()} for factory reconstruction. Optional
 * {@code extraData} stores JSON blobs for category-specific attributes without widening the schema.</p>
 */
public abstract class Item extends Entity {

    private String name;       // short display title (e.g. "Sony WH-1000XM5")
    private String description; // longer free-text description
    private long sellerId;     // the Seller who created this item
    private String sellerName; // denormalised for display
    private String imageUrl;   // optional image URL or local file path

    private String extraData;

    protected Item() { super(); }

    // Abstract category declaration

    public abstract ItemCategory getCategory();

    // Polymorphic display

    @Override
    public void printInfo() {
        // getCategory() resolves at runtime to Electronics/Art/Vehicle - polymorphism.
        System.out.printf("[%s] id=%d  name=%-30s  seller=%s%n",
                getCategory(), id, name, sellerName);
    }

    // Getters / setters

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public long getSellerId() { return sellerId; }
    public void setSellerId(long sellerId) { this.sellerId = sellerId; }

    public String getSellerName() { return sellerName; }
    public void setSellerName(String sellerName) { this.sellerName = sellerName; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public String getExtraData() { return extraData; }
    public void setExtraData(String extraData) { this.extraData = extraData; }
}
