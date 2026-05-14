package com.auction.server.model;

/**
 * Item taxonomy mirrored by {@link com.auction.server.model.Item} subclasses and {@link com.auction.server.factory.ItemFactory}.
 */
public enum ItemCategory {
    ELECTRONICS,  // consumer electronics, computers, phones, etc.
    ART,          // paintings, sculptures, collectibles, antiques
    VEHICLE       // cars, motorcycles, boats, aircraft
}
