package com.auction.server.dao.impl;

import com.auction.server.util.DateUtil;       // robust date-time parser (handles SQLite + Java formats)
import com.auction.server.dao.UserDAO;         // the interface this class implements
import com.auction.server.db.DatabaseManager;  // singleton that holds the shared SQLite connection
import com.auction.server.model.*;             // User, Bidder, Seller, Admin, UserRole

import java.sql.*;                             // JDBC: Connection, PreparedStatement, ResultSet, Statement
import java.time.LocalDateTime;                // used indirectly via DateUtil
import java.util.ArrayList;                    // mutable list for building results
import java.util.List;                         // return type for findAll()
import java.util.Optional;                     // null-safe single-result container

/**
 * JDBC-backed {@link com.auction.server.dao.UserDAO} for SQLite.
 *
 * <p><b>PreparedStatement:</b> Every variable user input is bound with {@code ?} placeholders to
 * avoid SQL injection. {@link com.auction.server.factory.UserFactory} rebuilds the proper subclass
 * when reading {@code role} text back from disk.</p>
 *
 * <p><b>Synchronization:</b> Methods are {@code synchronized} because SQLite allows only one writer
 * at a time — coarse-grained locking keeps the lab implementation simple.</p>
 */
public final class SQLiteUserDAO implements UserDAO {

    // Retrieve the shared connection from the Singleton - never store it locally
    // because DatabaseManager may re-open it if it was closed.
    private Connection conn() { return DatabaseManager.getInstance().getConnection(); }

    @Override
    // synchronized means only one thread at a time can run this database method on this DAO object.
    // SQLite is happiest when writes are kept simple and serialized like this.
    public synchronized User save(User user) {
        // INSERT with ? placeholders - values bound via setString/setInt below.
        String sql = """
            INSERT INTO users (username, password_hash, email, role, active, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
        """;
        // RETURN_GENERATED_KEYS tells JDBC to make the AUTOINCREMENT id available
        // in the ResultSet returned by getGeneratedKeys().
        // PreparedStatement is the JDBC object for SQL with placeholders.
        // The question marks are filled by setString and setInt, so user text is treated as data, not SQL code.
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, user.getUsername());
            ps.setString(2, user.getPasswordHash());
            ps.setString(3, user.getEmail());
            ps.setString(4, user.getRole().name()); // enum -> "BIDDER" / "SELLER" / "ADMIN"
            ps.setInt(5, user.isActive() ? 1 : 0);  // SQLite has no BOOLEAN - use 0/1
            ps.setString(6, user.getCreatedAt().toString()); // ISO-8601 string
            ps.executeUpdate();
            try (ResultSet keys = conn().createStatement().executeQuery("SELECT last_insert_rowid()")) {
                if (keys.next()) user.setId(keys.getLong(1)); // store the DB-assigned id
            }
            return user;
        } catch (SQLException e) {
            // Wrap in RuntimeException so callers don't need 'throws SQLException'.
            throw new RuntimeException("Failed to save user: " + e.getMessage(), e);
        }
    }

    @Override
    // synchronized prevents this read from using the shared SQLite connection at the same time as a write.
    public synchronized Optional<User> findById(long id) {
        String sql = "SELECT * FROM users WHERE id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setLong(1, id);
            return Optional.ofNullable(mapSingle(ps.executeQuery())); // null -> empty Optional
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    // This uses the same JDBC pattern: prepare SQL, bind values, execute, then map the ResultSet.
    public synchronized Optional<User> findByUsername(String username) {
        String sql = "SELECT * FROM users WHERE username = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, username);
            return Optional.ofNullable(mapSingle(ps.executeQuery()));
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public synchronized List<User> findAll() {
        String sql = "SELECT * FROM users ORDER BY id";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            return mapList(ps.executeQuery());
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public synchronized void updateActive(long userId, boolean active) {
        String sql = "UPDATE users SET active = ? WHERE id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, active ? 1 : 0);
            ps.setLong(2, userId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // ResultSet is JDBC's cursor over rows returned by a SELECT.
    // Think of it as a reader that starts before the first row and moves forward with next().

    // Reads exactly one row; returns null if the ResultSet is empty.
    // Callers wrap the return in Optional.ofNullable().
    private User mapSingle(ResultSet rs) throws SQLException {
        if (!rs.next()) return null;
        return map(rs);
    }

    // Reads all rows into a list.
    private List<User> mapList(ResultSet rs) throws SQLException {
        List<User> list = new ArrayList<>();
        while (rs.next()) list.add(map(rs));
        return list;
    }

    private User map(ResultSet rs) throws SQLException {
        UserRole role = UserRole.valueOf(rs.getString("role")); // "BIDDER" -> BIDDER enum
        User user = switch (role) {
            case BIDDER -> new com.auction.server.model.Bidder();
            case SELLER -> new com.auction.server.model.Seller();
            case ADMIN  -> new com.auction.server.model.Admin();
        };
        user.setId(rs.getLong("id"));
        user.setUsername(rs.getString("username"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setEmail(rs.getString("email"));
        user.setActive(rs.getInt("active") == 1); // 1 -> true, 0 -> false
        // DateUtil.parse() handles both "2026-04-22T14:32:07" and "2026-04-22 14:32:07"
        user.setCreatedAt(DateUtil.parse(rs.getString("created_at")));
        return user;
    }
}
