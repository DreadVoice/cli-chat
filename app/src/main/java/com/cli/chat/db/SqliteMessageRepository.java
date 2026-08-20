package com.cli.chat.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.cli.chat.common.Message;
import com.cli.chat.common.MessageType;
import com.cli.chat.common.exception.StorageException;

public class SqliteMessageRepository implements MessageRepository {

    private static final String INSERT =
            "INSERT INTO messages (type, sender, recipient, body, timestamp) VALUES (?, ?, ?, ?, ?)";
    private static final String SELECT_RECENT =
            "SELECT type, sender, recipient, body, timestamp FROM messages "
            + "ORDER BY timestamp DESC, id DESC LIMIT ?";
    private static final String SELECT_RECENT_FOR =
            "SELECT type, sender, recipient, body, timestamp FROM messages "
            + "WHERE recipient = ? ORDER BY timestamp DESC, id DESC LIMIT ?";

    private final Database database;

    public SqliteMessageRepository(Database database) {
        this.database = database;
    }

    @Override
    public long save(Message message) throws StorageException {
        try (Connection connection = database.open();
             PreparedStatement statement =
                     connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {

            statement.setString(1, message.type().name());
            statement.setString(2, message.sender());
            statement.setString(3, message.recipient());
            statement.setString(4, message.body());
            statement.setLong(5, message.timestamp());
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new StorageException("could not save message from " + message.sender(), e);
        }
    }

    @Override
    public void saveAll(List<Message> messages) throws StorageException {
        if (messages.isEmpty()) {
            return;
        }
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                for (Message message : messages) {
                    statement.setString(1, message.type().name());
                    statement.setString(2, message.sender());
                    statement.setString(3, message.recipient());
                    statement.setString(4, message.body());
                    statement.setLong(5, message.timestamp());
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new StorageException("could not save a batch of " + messages.size() + " messages", e);
        }
    }

    @Override
    public List<Message> recent(int limit) throws StorageException {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(SELECT_RECENT)) {

            statement.setInt(1, limit);
            return readAll(statement);
        } catch (SQLException e) {
            throw new StorageException("could not read the recent messages", e);
        }
    }

    @Override
    public List<Message> recentFor(String recipient, int limit) throws StorageException {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(SELECT_RECENT_FOR)) {

            statement.setString(1, recipient);
            statement.setInt(2, limit);
            return readAll(statement);
        } catch (SQLException e) {
            throw new StorageException("could not read the recent messages for " + recipient, e);
        }
    }

    private static List<Message> readAll(PreparedStatement statement) throws SQLException {
        List<Message> messages = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                messages.add(new Message(
                        MessageType.valueOf(rs.getString("type")),
                        rs.getString("sender"),
                        rs.getString("recipient"),
                        rs.getString("body"),
                        rs.getLong("timestamp")));
            }
        }
        Collections.reverse(messages);
        return List.copyOf(messages);
    }
}
