package com.auction.server.model;

/**
 * {@link Item} specialization for {@link ItemCategory#ART}.
 */
public final class Art extends Item {
    @Override public ItemCategory getCategory() { return ItemCategory.ART; }
}
