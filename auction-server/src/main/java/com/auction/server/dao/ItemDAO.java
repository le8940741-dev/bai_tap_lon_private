package com.auction.server.dao;

import com.auction.server.model.Item;

import java.util.List;
import java.util.Optional;

/**
 * DAO contract for items — maps OO {@link com.auction.server.model.Item} graphs to/from SQL rows.
 *
 * <p>Returns use {@link java.util.Optional} for single-row lookups so callers explicitly handle
 * “not found” instead of relying on {@code null}.</p>
 */
public interface ItemDAO {

    Item save(Item item);

    Optional<Item> findById(long id);

    List<Item> findBySellerId(long sellerId);
}
