package com.auction.server.model;

/**
 * FILE ROLE: Concrete item subclass for artwork, collectibles, antiques.
 *
 * Example extraData for an Art item:
 *   {"artist":"Pablo Picasso","medium":"Oil on canvas","year":1937}
 */
public final class Art extends Item {
    @Override public ItemCategory getCategory() { return ItemCategory.ART; }
}
