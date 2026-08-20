package com.cli.chat.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.cli.chat.common.exception.ChatException;
import com.cli.chat.common.exception.StorageException;

class DatabaseTest {

    @TempDir
    Path dir;

    private Database database;

    @BeforeEach
    void createDatabase() throws StorageException {
        database = new Database(dir.resolve("chat.db").toString());
        database.initialise();
    }

    @Test
    void initialiseCreatesTheUsersTable() throws Exception {
        try (Connection c = database.open();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'users'")) {

            assertTrue(rs.next(), "users table should exist");
            assertEquals("users", rs.getString("name"));
        }
    }

    @Test
    void initialiseIsSafeToRunTwice() throws Exception {
        database.initialise();

        insertUser("alice");
        assertEquals(1, countUsers());
    }

    @Test
    void usernamesAreUnique() throws Exception {
        insertUser("alice");

        assertThrows(SQLException.class, () -> insertUser("alice"));
        assertEquals(1, countUsers());
    }

    @Test
    void openFailsWithAStorageExceptionForAnUnusablePath() {
        Database broken = new Database(dir.resolve("missing-dir").resolve("chat.db").toString());

        StorageException e = assertThrows(StorageException.class, broken::open);
        assertInstanceOf(ChatException.class, e);
    }

    private void insertUser(String username) throws Exception {
        try (Connection c = database.open();
             PreparedStatement s = c.prepareStatement(
                     "INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)")) {
            s.setString(1, username);
            s.setString(2, "hash");
            s.setLong(3, 1L);
            s.executeUpdate();
        }
    }

    private int countUsers() throws Exception {
        try (Connection c = database.open();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM users")) {
            return rs.getInt(1);
        }
    }
}
