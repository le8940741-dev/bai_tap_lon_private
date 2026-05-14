package com.auction.server.model;

/**
 * {@link Item} specialization for {@link ItemCategory#ELECTRONICS} — no extra fields in this lab version.
 */
public final class Electronics extends Item {
    @Override public ItemCategory getCategory() { return ItemCategory.ELECTRONICS; }
}
