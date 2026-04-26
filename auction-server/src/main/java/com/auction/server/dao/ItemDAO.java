package com.auction.server.dao;

import com.auction.server.model.Item;

import java.util.List;
import java.util.Optional;

/**
 * FILE ROLE: DAO interface for Item persistence.
 *
 * Items are created by Sellers before opening an auction.
 * The DAO stores and retrieves Item subclasses (Electronics, Art, Vehicle)
 * using the 'category' column to reconstruct the correct concrete type
 * via ItemFactory when reading back from the database.
 *
 * IMPLEMENTED BY: SQLiteItemDAO
 * USED BY: ItemService
 */
public interface ItemDAO {

    /**
     * Persist a new Item to the database.
     * Sets item.id from the AUTOINCREMENT key before returning.
     *
     * @param item a populated Item subclass (Electronics/Art/Vehicle)
     * @return the same Item with id now set
     */
    Item save(Item item);

    /**
     * Find an item by its primary key.
     * Used by ItemService.getItem(), which is called by ClientHandler
     * when the Seller creates a new auction and references an existing item.
     */
    Optional<Item> findById(long id);

    /**
     * Find all items created by a specific seller.
     * Used by ItemService.getItemsBySeller(), which populates the ComboBox
     * in the SellerDashboard "Create Auction" form so the seller can pick
     * which of their items to auction.
     *
     * @param sellerId the Seller's user id
     */
    List<Item> findBySellerId(long sellerId);
}
