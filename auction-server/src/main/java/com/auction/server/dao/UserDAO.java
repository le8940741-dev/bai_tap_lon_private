package com.auction.server.dao;

import com.auction.server.model.User;

import java.util.List;     // ordered collection of users for findAll()
import java.util.Optional; // null-safe container for single-result queries

/**
 * DAO interface for {@code users} table access — part of the <b>Data Access Object</b> pattern.
 *
 * <p>Implementations (e.g. {@link com.auction.server.dao.impl.SQLiteUserDAO}) hide SQL strings and
 * JDBC details. Services depend only on this interface so unit tests can supply Mockito stubs.</p>
 */
public interface UserDAO {

    User save(User user);

    Optional<User> findById(long id);

    Optional<User> findByUsername(String username);

    List<User> findAll();

    void updateActive(long userId, boolean active);
}
