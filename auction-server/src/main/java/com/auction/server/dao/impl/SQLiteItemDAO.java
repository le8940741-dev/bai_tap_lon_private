package com.auction.server.dao.impl;

import com.auction.server.util.DateUtil;       // date parser that handles SQLite space-separator format
import com.auction.server.dao.ItemDAO;         // interface this class implements
import com.auction.server.db.DatabaseManager;  // singleton connection holder
import com.auction.server.factory.ItemFactory; // creates the right Item subclass from the category string
import com.auction.server.model.Item;          // abstract base class for all item types
import com.auction.server.model.ItemCategory;  // enum used to select the concrete Item subclass

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC implementation of {@link com.auction.server.dao.ItemDAO} with joins for seller display names.
 *
 * <p>Demonstrates how polymorphic {@link com.auction.server.model.Item} instances are rehydrated
 * from a {@code category} string column using {@link com.auction.server.factory.ItemFactory}.</p>
 */
public final class SQLiteItemDAO implements ItemDAO {

    private Connection conn() { return DatabaseManager.getInstance().getConnection(); }

    @Override
    public synchronized Item save(Item item) {
        String sql = """
            INSERT INTO items (name, description, category, seller_id, image_url, extra_data, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, item.getName());
            ps.setString(2, item.getDescription());
            ps.setString(3, item.getCategory().name()); // "ELECTRONICS", "ART", or "VEHICLE"
            ps.setLong(4, item.getSellerId());
            ps.setString(5, item.getImageUrl());
            ps.setString(6, item.getExtraData());         // may be null - stored as NULL in DB
            ps.setString(7, item.getCreatedAt().toString());
            ps.executeUpdate();
            try (ResultSet keys = conn().createStatement().executeQuery("SELECT last_insert_rowid()")) {
                if (keys.next()) item.setId(keys.getLong(1));
            }
            return item;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save item", e);
        }
    }

    @Override
    public synchronized Optional<Item> findById(long id) {
        // JOIN to get seller_name without a second query.
        String sql = """
            SELECT i.*, u.username AS seller_name
            FROM items i JOIN users u ON i.seller_id = u.id
            WHERE i.id = ?
        """;
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setLong(1, id);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(map(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public synchronized List<Item> findBySellerId(long sellerId) {
        String sql = """
            SELECT i.*, u.username AS seller_name
            FROM items i JOIN users u ON i.seller_id = u.id
            WHERE i.seller_id = ?
            ORDER BY i.id DESC
        """;
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setLong(1, sellerId);
            return mapList(ps.executeQuery());
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private List<Item> mapList(ResultSet rs) throws SQLException {
        List<Item> list = new ArrayList<>();
        while (rs.next()) list.add(map(rs));
        return list;
    }

    private Item map(ResultSet rs) throws SQLException {
        ItemCategory category = ItemCategory.valueOf(rs.getString("category"));
        Item item = ItemFactory.create(category); // Factory Method - creates Electronics, Art, or Vehicle
        item.setId(rs.getLong("id"));
        item.setName(rs.getString("name"));
        item.setDescription(rs.getString("description"));
        item.setSellerId(rs.getLong("seller_id"));
        item.setSellerName(rs.getString("seller_name")); // from the JOIN alias
        item.setImageUrl(rs.getString("image_url"));
        item.setExtraData(rs.getString("extra_data"));
        item.setCreatedAt(DateUtil.parse(rs.getString("created_at")));
        return item;
    }
}
