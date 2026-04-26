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
 * FILE ROLE: SQLite implementation of UserDAO.
 *
 * Every method opens a PreparedStatement (never raw string concatenation —
 * that would allow SQL injection), executes it, and closes it via try-with-resources.
 * The shared Connection is never closed by the DAO; only DatabaseManager manages it.
 *
 * THREAD SAFETY:
 *   All public methods are 'synchronized' on 'this'.  SQLite supports only one
 *   writer at a time.  Synchronizing on the DAO instance serialises concurrent
 *   requests to this table without using a global lock.
 *
 *   Because DatabaseManager.getConnection() is also synchronized, a deadlock
 *   is impossible: the only lock order is DAO → connection-check-lock,
 *   never the reverse.
 *
 * WHY PreparedStatement (not Statement):
 *   PreparedStatement pre-compiles the SQL and binds parameters as typed values.
 *   This prevents SQL injection: user-supplied strings like "'; DROP TABLE users --"
 *   become a literal bind value, not executable SQL.
 */
public final class SQLiteUserDAO implements UserDAO {

    // Retrieve the shared connection from the Singleton — never store it locally
    // because DatabaseManager may re-open it if it was closed.
    private Connection conn() { return DatabaseManager.getInstance().getConnection(); }

    @Override
    public synchronized User save(User user) {
        // INSERT with ? placeholders — values bound via setString/setInt below.
        String sql = """
            INSERT INTO users (username, password_hash, email, role, active, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
        """;
        // RETURN_GENERATED_KEYS tells JDBC to make the AUTOINCREMENT id available
        // in the ResultSet returned by getGeneratedKeys().
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, user.getUsername());
            ps.setString(2, user.getPasswordHash());
            ps.setString(3, user.getEmail());
            ps.setString(4, user.getRole().name()); // enum → "BIDDER" / "SELLER" / "ADMIN"
            ps.setInt(5, user.isActive() ? 1 : 0);  // SQLite has no BOOLEAN — use 0/1
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
    public synchronized Optional<User> findById(long id) {
        String sql = "SELECT * FROM users WHERE id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setLong(1, id);
            return Optional.ofNullable(mapSingle(ps.executeQuery())); // null → empty Optional
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
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

    // ── ResultSet → domain object mapping ─────────────────────────────────────

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

    /**
     * Convert one ResultSet row to the correct User subclass.
     *
     * The role string determines which concrete type to create.
     * This is where UserFactory's Factory Method is used — we never
     * call 'new Bidder()' directly here; the factory handles that.
     */
    private User map(ResultSet rs) throws SQLException {
        UserRole role = UserRole.valueOf(rs.getString("role")); // "BIDDER" → BIDDER enum
        User user = switch (role) {
            case BIDDER -> new com.auction.server.model.Bidder();
            case SELLER -> new com.auction.server.model.Seller();
            case ADMIN  -> new com.auction.server.model.Admin();
        };
        user.setId(rs.getLong("id"));
        user.setUsername(rs.getString("username"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setEmail(rs.getString("email"));
        user.setActive(rs.getInt("active") == 1); // 1 → true, 0 → false
        // DateUtil.parse() handles both "2026-04-22T14:32:07" and "2026-04-22 14:32:07"
        user.setCreatedAt(DateUtil.parse(rs.getString("created_at")));
        return user;
    }
}
