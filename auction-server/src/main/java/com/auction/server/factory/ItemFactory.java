package com.auction.server.factory;

import com.auction.server.model.Art;          // concrete Art item subclass
import com.auction.server.model.Electronics;  // concrete Electronics item subclass
import com.auction.server.model.Item;         // abstract base returned by the factory
import com.auction.server.model.ItemCategory; // enum that selects which subclass to create
import com.auction.server.model.Vehicle;      // concrete Vehicle item subclass

/**
 * FILE ROLE: Factory Method pattern for Item creation.
 *
 * PATTERN: Factory Method (GoF)
 *   Instead of calling "new Electronics()", "new Art()", or "new Vehicle()" in
 *   multiple places, all Item construction goes through this single method.
 *
 * WHY THIS MATTERS (Open/Closed Principle):
 *   When you add a new item category (e.g. RealEstate), you only change:
 *     1. Add REAL_ESTATE to ItemCategory enum.
 *     2. Add a new RealEstate class extending Item.
 *     3. Add a case here.
 *   Nothing else changes — SQLiteItemDAO, SQLiteAuctionDAO, DtoMapper all call
 *   ItemFactory.create(category) and automatically get the new type.
 *
 * USED BY:
 *   - ItemService.createItem()    — when a Seller creates a new item.
 *   - SQLiteItemDAO.map()         — when reading an item row from the database.
 *   - SQLiteAuctionDAO.map()      — when reading the embedded item from an auction row.
 */
public final class ItemFactory {

    private ItemFactory() {} // utility class — no instances

    /**
     * Create a new blank Item of the specified category.
     * The caller is responsible for setting name, description, sellerId, etc.
     *
     * Uses a switch expression (Java 14+) — exhaustive, so the compiler will
     * force you to add a case if you add a new ItemCategory constant.
     *
     * @param category the item category enum constant
     * @return a new, unpopulated Item subclass instance
     */
    public static Item create(ItemCategory category) {
        return switch (category) {
            case ELECTRONICS -> new Electronics();
            case ART         -> new Art();
            case VEHICLE     -> new Vehicle();
            // No 'default' — the switch must be exhaustive; compiler enforces this.
        };
    }

    /**
     * Convenience overload that accepts the category as a String.
     * Used by ItemService.createItem() which receives the string from the client's
     * CreateItemRequest.category field.
     *
     * @param categoryName e.g. "ELECTRONICS", "ART", "VEHICLE" (case-insensitive)
     * @throws IllegalArgumentException if the string doesn't match any enum constant
     */
    public static Item create(String categoryName) {
        // valueOf() throws IllegalArgumentException for unknown names — caught by ItemService.
        return create(ItemCategory.valueOf(categoryName.toUpperCase()));
    }
}
