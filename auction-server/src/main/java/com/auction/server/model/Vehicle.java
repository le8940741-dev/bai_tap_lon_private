package com.auction.server.model;

/**
 * FILE ROLE: Concrete item subclass for cars, motorcycles, boats, etc.
 *
 * Example extraData for a Vehicle item:
 *   {"make":"Toyota","model":"Supra","year":1995,"mileage_km":45000}
 */
public final class Vehicle extends Item {
    @Override public ItemCategory getCategory() { return ItemCategory.VEHICLE; }
}
