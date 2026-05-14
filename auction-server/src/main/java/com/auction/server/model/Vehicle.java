package com.auction.server.model;

/**
 * {@link Item} specialization for {@link ItemCategory#VEHICLE}.
 */
public final class Vehicle extends Item {
    @Override public ItemCategory getCategory() { return ItemCategory.VEHICLE; }
}
