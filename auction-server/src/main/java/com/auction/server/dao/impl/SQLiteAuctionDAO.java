package com.auction.server.dao.impl;

import com.auction.server.util.DateUtil;       // handles both SQLite and Java date formats
import com.auction.server.dao.AuctionDAO;      // interface this class implements
import com.auction.server.db.DatabaseManager;  // singleton connection holder
import com.auction.server.factory.ItemFactory; // reconstructs the correct Item subclass
import com.auction.server.model.*;             // Auction, AuctionStatus, Item, ItemCategory

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC implementation of {@link com.auction.server.dao.AuctionDAO} with multi-table SELECTs.
 *
 * <p><b>Runtime flow:</b> AuctionServer creates this DAO at server startup and injects it into
 * AuctionService and BidService; those services call it during client request handling and scheduler
 * close events.</p>
 *
 * <p>Because auctions embed an {@link com.auction.server.model.Item}, queries JOIN {@code items}
 * (and often users for seller / winner names). Watch {@code rs.wasNull()} when mapping nullable winner ids.</p>
 */
public final class SQLiteAuctionDAO implements AuctionDAO {

    private Connection conn() { return DatabaseManager.getInstance().getConnection(); }

    // FunctionalInterface: lets each query pass only its parameter-binding step into shared JDBC helpers.
    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private static final StatementBinder NO_BINDING = ps -> {};

    // The base SELECT that all read methods build on.
    // Aliases prevent column-name ambiguity when multiple tables have 'id' or 'created_at'.
    private static final String SELECT_BASE = """
        SELECT
            a.*,
            i.name          AS item_name,
            i.description   AS item_desc,
            i.category      AS item_category,
            i.seller_id     AS item_seller_id,
            i.image_url     AS item_image_url,
            i.extra_data    AS item_extra,
            i.created_at    AS item_created,
            s.username      AS seller_name,
            w.username      AS winner_name
        FROM auctions a
        JOIN items   i ON a.item_id   = i.id
        JOIN users   s ON a.seller_id = s.id
        LEFT JOIN users w ON a.winner_id = w.id
    """;
    // LEFT JOIN for winner: if no bids exist, winner_id is NULL and the join
    // produces NULL for winner_name rather than dropping the row entirely.

    @Override
    // synchronized serializes access to the shared SQLite connection for this DAO.
    // This matters because bid requests and scheduler events can both update auctions.
    public synchronized Auction save(Auction auction) {
        // item.id must already be set (ItemDAO.save() must have been called first).
        String sql = """
            INSERT INTO auctions
                (item_id, starting_price, current_price, start_time, end_time, status, seller_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setLong(1, auction.getItem().getId());
            ps.setDouble(2, auction.getStartingPrice());
            ps.setDouble(3, auction.getCurrentPrice());
            ps.setString(4, auction.getStartTime().toString());
            ps.setString(5, auction.getEndTime().toString());
            ps.setString(6, auction.getStatus().name()); // "OPEN" or "RUNNING"
            ps.setLong(7, auction.getSellerId());
            ps.setString(8, auction.getCreatedAt().toString());
            ps.executeUpdate();
            try (ResultSet keys = conn().createStatement().executeQuery("SELECT last_insert_rowid()")) {
                if (keys.next()) auction.setId(keys.getLong(1));
            }
            return auction;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save auction", e);
        }
    }

    @Override
    public synchronized Optional<Auction> findById(long id) {
        return findOne(SELECT_BASE + " WHERE a.id = ?",
                ps -> ps.setLong(1, id));
    }

    @Override
    public synchronized List<Auction> findAll() {
        return findMany(SELECT_BASE + " ORDER BY a.id DESC", NO_BINDING); // newest first
    }

    @Override
    public synchronized List<Auction> findBySellerId(long sellerId) {
        return findMany(SELECT_BASE + " WHERE a.seller_id = ? ORDER BY a.id DESC",
                ps -> ps.setLong(1, sellerId));
    }

    @Override
    public synchronized List<Auction> findByStatus(AuctionStatus status) {
        return findMany(SELECT_BASE + " WHERE a.status = ?",
                ps -> ps.setString(1, status.name()));
    }

    @Override
    public synchronized void updateStatus(long auctionId, AuctionStatus status) {
        try (PreparedStatement ps = conn().prepareStatement(
                "UPDATE auctions SET status = ? WHERE id = ?")) {
            ps.setString(1, status.name());
            ps.setLong(2, auctionId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public synchronized void updateCurrentPrice(long auctionId, double price, long leadingBidderId) {
        // Both price and winner are always updated together - they are logically atomic.
        try (PreparedStatement ps = conn().prepareStatement(
                "UPDATE auctions SET current_price = ?, winner_id = ? WHERE id = ?")) {
            ps.setDouble(1, price);
            ps.setLong(2, leadingBidderId);
            ps.setLong(3, auctionId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public synchronized void updateEndTime(long auctionId, LocalDateTime newEndTime) {
        try (PreparedStatement ps = conn().prepareStatement(
                "UPDATE auctions SET end_time = ? WHERE id = ?")) {
            ps.setString(1, newEndTime.toString()); // ISO-8601 with T separator
            ps.setLong(2, auctionId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public synchronized void updateWinner(long auctionId, long winnerId, AuctionStatus status) {
        try (PreparedStatement ps = conn().prepareStatement(
                "UPDATE auctions SET winner_id = ?, status = ? WHERE id = ?")) {
            ps.setLong(1, winnerId);
            ps.setString(2, status.name());
            ps.setLong(3, auctionId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // ResultSet -> domain object mapping
    // ResultSet is JDBC's reader over rows returned by a SELECT.
    // The helper methods below turn those rows into normal Java Auction objects.

    private Optional<Auction> findOne(String sql, StatementBinder binder) {
        // PreparedStatement is JDBC's safe way to run SQL with placeholders.
        // Each setLong, setDouble, and setString fills one question mark in the SQL.
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            binder.bind(ps);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(map(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private List<Auction> findMany(String sql, StatementBinder binder) {
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            binder.bind(ps);
            return mapList(ps.executeQuery());
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private List<Auction> mapList(ResultSet rs) throws SQLException {
        List<Auction> list = new ArrayList<>();
        while (rs.next()) list.add(map(rs));
        return list;
    }

    private Auction map(ResultSet rs) throws SQLException {
        Auction a = new Auction();
        a.setId(rs.getLong("id"));
        a.setStartingPrice(rs.getDouble("starting_price"));
        a.setCurrentPrice(rs.getDouble("current_price"));
        a.setStartTime(DateUtil.parse(rs.getString("start_time")));
        a.setEndTime(DateUtil.parse(rs.getString("end_time")));
        a.setStatus(AuctionStatus.valueOf(rs.getString("status")));
        a.setSellerId(rs.getLong("seller_id"));
        a.setSellerName(rs.getString("seller_name"));
        a.setCreatedAt(DateUtil.parse(rs.getString("created_at")));

        // Read the nullable winner columns.
        long winnerId = rs.getLong("winner_id");
        if (!rs.wasNull()) { // wasNull() must be checked immediately after getLong()
            a.setLeadingBidderId(winnerId);
            a.setLeadingBidderName(rs.getString("winner_name"));
        }

        // Reconstruct the embedded item from the JOIN columns.
        ItemCategory cat = ItemCategory.valueOf(rs.getString("item_category"));
        Item item = ItemFactory.create(cat); // Factory Method
        item.setId(rs.getLong("item_id"));
        item.setName(rs.getString("item_name"));
        item.setDescription(rs.getString("item_desc"));
        item.setSellerId(rs.getLong("item_seller_id"));
        item.setSellerName(rs.getString("seller_name")); // same seller as auction
        item.setImageUrl(rs.getString("item_image_url"));
        item.setExtraData(rs.getString("item_extra"));
        item.setCreatedAt(DateUtil.parse(rs.getString("item_created")));
        a.setItem(item);

        return a;
    }
}
