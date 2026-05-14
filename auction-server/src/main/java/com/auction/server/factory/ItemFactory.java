package com.auction.server.factory;

import com.auction.server.model.Art;          // concrete Art item subclass
import com.auction.server.model.Electronics;  // concrete Electronics item subclass
import com.auction.server.model.Item;         // abstract base returned by the factory
import com.auction.server.model.ItemCategory; // enum that selects which subclass to create
import com.auction.server.model.Vehicle;      // concrete Vehicle item subclass

/**
 * Factory Method pattern for {@link com.auction.server.model.Item} subclasses.
 *
 * <p>Each {@link com.auction.server.model.ItemCategory} maps to a concrete class so new categories
 * can be introduced by adding one enum constant, one model class, and one {@code case} here.</p>
 */
public final class ItemFactory {

    private ItemFactory() {} // utility class - no instances

    public static Item create(ItemCategory category) {
        return switch (category) {
            case ELECTRONICS -> new Electronics();
            case ART         -> new Art();
            case VEHICLE     -> new Vehicle();
            // No 'default' - the switch must be exhaustive; compiler enforces this.
        };
    }

    public static Item create(String categoryName) {
        // valueOf() throws IllegalArgumentException for unknown names - caught by ItemService.
        return create(ItemCategory.valueOf(categoryName.toUpperCase()));
    }
}
