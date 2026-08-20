package com.cli.chat.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cli.chat.common.Message;
import com.cli.chat.common.MessageType;
import com.cli.chat.common.exception.StorageException;

class SqliteMessageRepositoryTest {

    private InMemoryDatabase database;

    private MessageRepository messages;

    @BeforeEach
    void createRepository() throws StorageException {
        database = InMemoryDatabase.create();
        messages = new SqliteMessageRepository(database.database());
    }

    @AfterEach
    void closeDatabase() throws Exception {
        database.close();
    }

    private static Message broadcast(String sender, String body, long timestamp) {
        return new Message(MessageType.BROADCAST, sender, null, body, timestamp);
    }

    private static Message privateMessage(String sender, String recipient, long timestamp) {
        return new Message(MessageType.PRIVATE, sender, recipient, "psst", timestamp);
    }

    @Test
    void savedMessageRoundTrips() throws Exception {
        Message original = broadcast("alice", "hello", 1000L);

        long id = messages.save(original);

        assertNotEquals(0L, id);
        assertEquals(List.of(original), messages.recent(10));
    }

    @Test
    void aNullRecipientStaysNull() throws Exception {
        messages.save(broadcast("alice", "hello", 1000L));

        assertNull(messages.recent(10).get(0).recipient());
    }

    @Test
    void recentReturnsTheNewestMessagesInChronologicalOrder() throws Exception {
        messages.save(broadcast("alice", "first", 1000L));
        messages.save(broadcast("bob", "second", 2000L));
        messages.save(broadcast("carol", "third", 3000L));

        List<Message> last = messages.recent(2);

        assertEquals(List.of("second", "third"), last.stream().map(Message::body).toList());
    }

    @Test
    void recentIsEmptyForAFreshDatabase() throws Exception {
        assertTrue(messages.recent(10).isEmpty());
    }

    @Test
    void recentForOnlyReturnsMessagesAddressedToThatUser() throws Exception {
        messages.save(broadcast("alice", "everyone", 1000L));
        messages.save(privateMessage("alice", "bob", 2000L));
        messages.save(privateMessage("alice", "carol", 3000L));

        List<Message> forBob = messages.recentFor("bob", 10);

        assertEquals(1, forBob.size());
        assertEquals("bob", forBob.get(0).recipient());
    }

    @Test
    void messagesSharingATimestampKeepTheirInsertionOrder() throws Exception {
        messages.save(broadcast("alice", "first", 1000L));
        messages.save(broadcast("alice", "second", 1000L));

        assertEquals(List.of("first", "second"),
                messages.recent(10).stream().map(Message::body).toList());
    }

    @Test
    void saveAllStoresEveryMessageInOrder() throws Exception {
        messages.saveAll(List.of(
                broadcast("alice", "first", 1000L),
                broadcast("bob", "second", 2000L),
                broadcast("carol", "third", 3000L)));

        assertEquals(List.of("first", "second", "third"),
                messages.recent(10).stream().map(Message::body).toList());
    }

    @Test
    void saveAllAcceptsAnEmptyBatch() throws Exception {
        messages.saveAll(List.of());

        assertTrue(messages.recent(10).isEmpty());
    }

    @Test
    void aFailedBatchIsRolledBackEntirely() throws Exception {
        messages.save(broadcast("alice", "before", 500L));

        Message unwritable = new Message(MessageType.BROADCAST, null, null, "poison", 1000L);
        assertThrows(StorageException.class, () -> messages.saveAll(List.of(
                broadcast("alice", "one", 1000L),
                broadcast("alice", "two", 2000L),
                unwritable)));

        assertEquals(List.of("before"), messages.recent(10).stream().map(Message::body).toList());
    }

    @Test
    void saveAllMixesWithSave() throws Exception {
        messages.save(broadcast("alice", "single", 1000L));
        messages.saveAll(List.of(broadcast("bob", "batched", 2000L)));

        assertEquals(List.of("single", "batched"),
                messages.recent(10).stream().map(Message::body).toList());
    }
}
