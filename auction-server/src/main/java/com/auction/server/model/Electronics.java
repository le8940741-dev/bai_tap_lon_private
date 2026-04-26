package com.auction.server.model;

/**
 * FILE ROLE: Concrete item subclass for electronic goods (phones, laptops, etc.).
 *
 * Inherits all fields from Item.  getCategory() returns ELECTRONICS which is
 * used by ItemFactory.create("ELECTRONICS") and by DtoMapper to set the
 * category string in ItemDTO.
 *
 * Example extraData for an Electronics item:
 *   {"brand":"Sony","model":"WH-1000XM5","warranty":"2yr"}
 */
public final class Electronics extends Item {
    @Override public ItemCategory getCategory() { return ItemCategory.ELECTRONICS; }
}
