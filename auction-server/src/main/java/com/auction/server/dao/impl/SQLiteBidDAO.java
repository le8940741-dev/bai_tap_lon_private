package com.auction.server.dao.impl;

import com.auction.server.util.DateUtil;       // parses stored timestamp strings
import com.auction.server.dao.BidDAO;          // interface this class implements
import com.auction.server.db.DatabaseManager;  // singleton connection holder
import com.auction.server.model.BidTransaction; // the domain object we persist and read

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists each accepted bid as an immutable history row ordered ascending by time.
 *
 * <p>This separation keeps {@link com.auction.server.dao.AuctionDAO} smaller and gives the UI a
 * dedicated query for charts without scanning the whole auctions table.</p>
 */
public final class SQLiteBidDAO implements BidDAO {

    private Connection conn() { return DatabaseManager.getInstance().getConnection(); }

    @Override
    // synchronized keeps bid writes from overlapping on the one shared SQLite connection.
    public synchronized BidTransaction save(BidTransaction bid) {
        String sql = """
            INSERT INTO bid_transactions (auction_id, bidder_id, amount, is_auto_bid, created_at)
            VALUES (?, ?, ?, ?, ?)
        """;
        // PreparedStatement sends this INSERT to SQLite with the values bound separately.
        // That keeps the SQL shape fixed and lets JDBC handle quoting values correctly.
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setLong(1, bid.getAuctionId());
            ps.setLong(2, bid.getBidderId());
            ps.setDouble(3, bid.getAmount());
            ps.setInt(4, bid.isAutoBid() ? 1 : 0); // boolean -> SQLite integer
            ps.setString(5, bid.getTimestamp().toString()); // ISO-8601 string
            ps.executeUpdate();
            // SQLite creates the id automatically. This small ResultSet reads back the id SQLite just assigned.
            try (ResultSet keys = conn().createStatement().executeQuery("SELECT last_insert_rowid()")) {
                if (keys.next()) bid.setId(keys.getLong(1));
            }
            return bid;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save bid", e);
        }
    }

    @Override
    // synchronized protects the shared connection while reading bid history.
    public synchronized List<BidTransaction> findByAuctionId(long auctionId) {
        String sql = """
            SELECT bt.*, u.username AS bidder_name
            FROM bid_transactions bt
            JOIN users u ON bt.bidder_id = u.id
            WHERE bt.auction_id = ?
            ORDER BY bt.created_at ASC
        """;
        // ASC order: first bid first - matches the left-to-right chart X axis.
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            // executeQuery returns a ResultSet, which is a cursor over the rows SQLite found.
            ResultSet rs = ps.executeQuery();
            List<BidTransaction> list = new ArrayList<>();
            while (rs.next()) {
                BidTransaction bt = new BidTransaction();
                bt.setId(rs.getLong("id"));
                bt.setAuctionId(rs.getLong("auction_id"));
                bt.setBidderId(rs.getLong("bidder_id"));
                bt.setBidderName(rs.getString("bidder_name")); // from JOIN alias
                bt.setAmount(rs.getDouble("amount"));
                bt.setAutoBid(rs.getInt("is_auto_bid") == 1); // integer -> boolean
                bt.setTimestamp(DateUtil.parse(rs.getString("created_at")));
                list.add(bt);
            }
            return list;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
}
