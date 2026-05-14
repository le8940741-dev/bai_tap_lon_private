package com.auction.server.dao.impl;

import com.auction.server.util.DateUtil;       // parses the registered_at timestamp
import com.auction.server.dao.AutoBidDAO;      // interface this class implements
import com.auction.server.db.DatabaseManager;  // singleton connection holder
import com.auction.server.model.AutoBid;       // the domain object we persist and read

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stores auto-bid ceilings per (auction, bidder) pair using UPSERT semantics.
 *
 * <p>SQLite’s {@code INSERT ... ON CONFLICT DO UPDATE} keeps one row per bidder even if they
 * tweak settings repeatedly — study {@link #save} for a transactional pattern example.</p>
 */
public final class SQLiteAutoBidDAO implements AutoBidDAO {

    private Connection conn() { return DatabaseManager.getInstance().getConnection(); }

    @Override
    // synchronized prevents two threads from saving auto-bid settings through this DAO at the same time.
    public synchronized AutoBid save(AutoBid ab) {
        // UPSERT: INSERT the row; if the unique key (auction_id, bidder_id) conflicts,
        // update the existing row's max_bid, increment, and registration time instead.
        String sql = """
            INSERT INTO auto_bids (auction_id, bidder_id, max_bid, increment, registered_at, active)
            VALUES (?, ?, ?, ?, ?, 1)
            ON CONFLICT(auction_id, bidder_id) DO UPDATE SET
                max_bid       = excluded.max_bid,
                increment     = excluded.increment,
                registered_at = excluded.registered_at,
                active        = 1
        """;
        // 'excluded' refers to the row that was attempted to be inserted (the new values).
        // PreparedStatement binds Java values into the SQL placeholders.
        // The UPSERT rule is still handled by SQLite after JDBC sends the statement.
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setLong(1, ab.getAuctionId());
            ps.setLong(2, ab.getBidderId());
            ps.setDouble(3, ab.getMaxBid());
            ps.setDouble(4, ab.getIncrement());
            ps.setString(5, ab.getRegisteredAt().toString());
            ps.executeUpdate();
            try (ResultSet keys = conn().createStatement().executeQuery("SELECT last_insert_rowid()")) {
                if (keys.next()) ab.setId(keys.getLong(1));
            }
            return ab;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save auto-bid", e);
        }
    }

    @Override
    // synchronized keeps this read consistent with other uses of the shared SQLite connection.
    public synchronized List<AutoBid> findActiveByAuctionId(long auctionId) {
        String sql = """
            SELECT ab.*, u.username AS bidder_name
            FROM auto_bids ab JOIN users u ON ab.bidder_id = u.id
            WHERE ab.auction_id = ? AND ab.active = 1
            ORDER BY ab.registered_at ASC
        """;
        // active = 1 filter: exhausted auto-bids are invisible to the algorithm.
        // ASC order: earlier registrations win ties - read in tie-break order already.
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            return mapList(ps.executeQuery());
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    // Optional is used because the SELECT might find no row for this bidder and auction.
    public synchronized Optional<AutoBid> findByAuctionAndBidder(long auctionId, long bidderId) {
        String sql = """
            SELECT ab.*, u.username AS bidder_name
            FROM auto_bids ab JOIN users u ON ab.bidder_id = u.id
            WHERE ab.auction_id = ? AND ab.bidder_id = ?
        """;
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            ps.setLong(2, bidderId);
            // ResultSet lets us ask "was there a row?" with next(), then read its columns.
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(map(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    // synchronized protects this UPDATE because it changes the database file.
    public synchronized void deactivate(long autoBidId) {
        // Set active=0 - the row is kept for audit purposes but no longer queried
        // by findActiveByAuctionId().
        try (PreparedStatement ps = conn().prepareStatement(
                "UPDATE auto_bids SET active = 0 WHERE id = ?")) {
            ps.setLong(1, autoBidId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private List<AutoBid> mapList(ResultSet rs) throws SQLException {
        List<AutoBid> list = new ArrayList<>();
        while (rs.next()) list.add(map(rs));
        return list;
    }

    private AutoBid map(ResultSet rs) throws SQLException {
        AutoBid ab = new AutoBid();
        ab.setId(rs.getLong("id"));
        ab.setAuctionId(rs.getLong("auction_id"));
        ab.setBidderId(rs.getLong("bidder_id"));
        ab.setBidderName(rs.getString("bidder_name")); // from JOIN alias
        ab.setMaxBid(rs.getDouble("max_bid"));
        ab.setIncrement(rs.getDouble("increment"));
        ab.setRegisteredAt(DateUtil.parse(rs.getString("registered_at")));
        ab.setActive(rs.getInt("active") == 1);
        return ab;
    }
}
