package com.cli.chat.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import com.cli.chat.common.User;
import com.cli.chat.common.exception.StorageException;
import com.cli.chat.common.exception.UsernameTakenException;

public class SqliteUserRepository implements UserRepository {

    private static final String INSERT =
            "INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)";
    private static final String SELECT_BY_USERNAME =
            "SELECT id, username, password_hash, created_at FROM users WHERE username = ?";

    private final Database database;

    public SqliteUserRepository(Database database) {
        this.database = database;
    }

    @Override
    public User create(String username, String passwordHash)
            throws StorageException, UsernameTakenException {
        long createdAt = System.currentTimeMillis();
        try (Connection connection = database.open();
             PreparedStatement statement =
                     connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {

            statement.setString(1, username);
            statement.setString(2, passwordHash);
            statement.setLong(3, createdAt);
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return new User(keys.getLong(1), username, passwordHash, createdAt);
            }
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                throw new UsernameTakenException(username);
            }
            throw new StorageException("could not create user " + username, e);
        }
    }

    @Override
    public Optional<User> findByUsername(String username) throws StorageException {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(SELECT_BY_USERNAME)) {

            statement.setString(1, username);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new User(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getLong("created_at")));
            }
        } catch (SQLException e) {
            throw new StorageException("could not look up user " + username, e);
        }
    }

    @Override
    public boolean exists(String username) throws StorageException {
        return findByUsername(username).isPresent();
    }

    private static boolean isUniqueViolation(SQLException e) {
        return e instanceof SQLiteException sqlite
                && sqlite.getResultCode() == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE;
    }
}
