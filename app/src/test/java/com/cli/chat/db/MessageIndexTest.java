package com.cli.chat.db;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cli.chat.common.exception.StorageException;

class MessageIndexTest {

    private InMemoryDatabase database;

    @BeforeEach
    void createDatabase() throws StorageException {
        database = InMemoryDatabase.create();
    }

    @AfterEach
    void closeDatabase() throws Exception {
        database.close();
    }

    private String planFor(String sql, Object... parameters) throws Exception {
        try (Connection c = database.database().open();
             PreparedStatement s = c.prepareStatement("EXPLAIN QUERY PLAN " + sql)) {

            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            StringBuilder plan = new StringBuilder();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    plan.append(rs.getString("detail")).append('\n');
                }
            }
            return plan.toString();
        }
    }

    @Test
    void recentUsesTheTimestampIndexInsteadOfSorting() throws Exception {
        String plan = planFor("SELECT type, sender, recipient, body, timestamp FROM messages "
                + "ORDER BY timestamp DESC, id DESC LIMIT ?", 50);

        assertTrue(plan.contains("idx_messages_recent"), plan);
        assertFalse(plan.contains("USE TEMP B-TREE FOR ORDER BY"), plan);
    }

    @Test
    void recentForUsesTheRecipientIndexInsteadOfScanning() throws Exception {
        String plan = planFor("SELECT type, sender, recipient, body, timestamp FROM messages "
                + "WHERE recipient = ? ORDER BY timestamp DESC, id DESC LIMIT ?", "bob", 50);

        assertTrue(plan.contains("idx_messages_recipient_recent"), plan);
        assertFalse(plan.contains("SCAN messages"), plan);
    }

    @Test
    void usernameLookupUsesTheUniqueConstraintIndex() throws Exception {
        String plan = planFor("SELECT id FROM users WHERE username = ?", "alice");

        assertTrue(plan.contains("sqlite_autoindex_users_1"), plan);
    }
}
