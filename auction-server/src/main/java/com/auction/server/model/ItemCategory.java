package com.auction.server.model;

/**
 * FILE ROLE: Enumeration of supported item categories.
 *
 * Stored as TEXT in SQLite with a CHECK constraint to prevent invalid values.
 * Each constant maps to a concrete Item subclass:
 *   ELECTRONICS → Electronics
 *   ART         → Art
 *   VEHICLE     → Vehicle
 *
 * Used in:
 *   - Item.getCategory() — each subclass returns its constant.
 *   - ItemFactory.create(category) — switches on this to instantiate the right subclass.
 *   - SQLiteItemDAO.map() / SQLiteAuctionDAO.map() — parse the TEXT column back to enum.
 */
public enum ItemCategory {
    ELECTRONICS,  // consumer electronics, computers, phones, etc.
    ART,          // paintings, sculptures, collectibles, antiques
    VEHICLE       // cars, motorcycles, boats, aircraft
}
