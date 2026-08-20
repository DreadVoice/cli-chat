package com.cli.chat.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cli.chat.common.Message;
import com.cli.chat.common.MessageType;
import com.cli.chat.common.exception.StorageException;

class MessageWriterTest {

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

    private static Message chat(String body, long timestamp) {
        return new Message(MessageType.BROADCAST, "alice", null, body, timestamp);
    }

    @Test
    void submittedMessagesReachTheRepository() throws Exception {
        try (MessageWriter writer = new MessageWriter(messages)) {
            writer.start();

            assertTrue(writer.submit(chat("first", 1000L)));
            assertTrue(writer.submit(chat("second", 2000L)));
        }

        assertEquals(List.of("first", "second"),
                messages.recent(10).stream().map(Message::body).toList());
    }

    @Test
    void closeDrainsWhatIsStillQueued() throws Exception {
        MessageWriter writer = new MessageWriter(messages);
        writer.start();
        for (int i = 0; i < 500; i++) {
            writer.submit(chat("m" + i, 1000L + i));
        }

        writer.close();

        assertEquals(500, messages.recent(1000).size(), "close must not discard queued messages");
    }

    @Test
    void submitDoesNotBlockTheCaller() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        MessageRepository slow = new MessageRepository() {
            @Override
            public long save(Message message) throws StorageException {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return messages.save(message);
            }

            @Override
            public List<Message> recent(int limit) throws StorageException {
                return messages.recent(limit);
            }

            @Override
            public List<Message> recentFor(String recipient, int limit) throws StorageException {
                return messages.recentFor(recipient, limit);
            }
        };

        try (MessageWriter writer = new MessageWriter(slow)) {
            writer.start();

            long start = System.nanoTime();
            writer.submit(chat("blocked", 1000L));
            writer.submit(chat("still fast", 2000L));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(elapsedMs < 1000, "submit returned only after " + elapsedMs + " ms");
            release.countDown();
        }
    }

    @Test
    void aFullQueueDropsInsteadOfBlocking() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean saved = new AtomicBoolean();
        MessageRepository blocked = new MessageRepository() {
            @Override
            public long save(Message message) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                saved.set(true);
                return 1L;
            }

            @Override
            public List<Message> recent(int limit) {
                return List.of();
            }

            @Override
            public List<Message> recentFor(String recipient, int limit) {
                return List.of();
            }
        };

        MessageWriter writer = new MessageWriter(blocked, 1);
        writer.start();
        writer.submit(chat("taken by the writer", 1000L));
        assertTrue(entered.await(5, TimeUnit.SECONDS), "writer should be busy saving the first message");
        assertTrue(writer.submit(chat("fills the queue", 2000L)));

        assertFalse(writer.submit(chat("dropped", 3000L)), "a full queue must reject, not block");
        assertEquals(1, writer.droppedCount());

        release.countDown();
        writer.close();
        assertTrue(saved.get());
    }

    @Test
    void aFailingSaveDoesNotKillTheWriter() throws Exception {
        AtomicBoolean failNext = new AtomicBoolean(true);
        MessageRepository flaky = new MessageRepository() {
            @Override
            public long save(Message message) throws StorageException {
                if (failNext.getAndSet(false)) {
                    throw new StorageException("disk on fire");
                }
                return messages.save(message);
            }

            @Override
            public List<Message> recent(int limit) throws StorageException {
                return messages.recent(limit);
            }

            @Override
            public List<Message> recentFor(String recipient, int limit) throws StorageException {
                return messages.recentFor(recipient, limit);
            }
        };

        try (MessageWriter writer = new MessageWriter(flaky)) {
            writer.start();
            writer.submit(chat("lost", 1000L));
            writer.submit(chat("survives", 2000L));
        }

        assertEquals(List.of("survives"), messages.recent(10).stream().map(Message::body).toList());
    }

    @Test
    void submitAfterCloseIsRejected() throws Exception {
        MessageWriter writer = new MessageWriter(messages);
        writer.start();
        writer.close();

        assertFalse(writer.submit(chat("too late", 1000L)));
        assertTrue(messages.recent(10).isEmpty());
    }
}
