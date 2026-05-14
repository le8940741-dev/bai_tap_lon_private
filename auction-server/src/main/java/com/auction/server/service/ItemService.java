package com.auction.server.service;

import com.auction.server.dao.ItemDAO;              // persistence for items
import com.auction.server.exception.AuctionException; // thrown for rule violations
import com.auction.server.factory.ItemFactory;      // creates correct Item subclass
import com.auction.server.model.Item;               // abstract base for all item types
import com.auction.server.model.User;               // used to check if requester is a Seller
import com.auction.server.model.UserRole;           // role constant for permission check

import java.util.List; // return type for findBySellerId

/**
 * Wraps {@link com.auction.server.dao.ItemDAO} with permission checks for sellers.
 *
 * <p><b>Factory pattern:</b> Uses {@link com.auction.server.factory.ItemFactory} so adding a new
 * {@link com.auction.server.model.ItemCategory} does not require editing every switch that builds items.</p>
 */
public final class ItemService {

    private final ItemDAO itemDAO; // only dependency; injected for testability

    public ItemService(ItemDAO itemDAO) {
        this.itemDAO = itemDAO;
    }

    public Item createItem(String name, String description,
                           String category, String extraData,
                           String imageUrl, User seller) {
        // Only Sellers may list items - Bidders and Admins are rejected.
        if (seller.getRole() != UserRole.SELLER)
            throw new AuctionException("Only sellers can create items");
        if (name == null || name.isBlank())
            throw new AuctionException("Item name must not be blank");

        // ItemFactory.create(category) throws IllegalArgumentException for unknown
        // category strings - that propagates up as an unchecked exception.
        Item item = ItemFactory.create(category);
        item.setName(name.trim());         // trim trailing/leading whitespace
        item.setDescription(description);
        item.setSellerId(seller.getId());
        item.setSellerName(seller.getUsername());
        item.setImageUrl(imageUrl == null || imageUrl.isBlank() ? null : imageUrl.trim());
        item.setExtraData(extraData);      // may be null - stored as NULL in DB

        return itemDAO.save(item); // DAO sets item.id from AUTOINCREMENT key
    }

    public Item getItem(long itemId) {
        return itemDAO.findById(itemId)
                .orElseThrow(() -> new AuctionException("Item not found: " + itemId));
    }

    public List<Item> getItemsBySeller(long sellerId) {
        return itemDAO.findBySellerId(sellerId);
    }
}
