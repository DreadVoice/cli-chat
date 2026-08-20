package com.cli.chat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.cli.chat.common.Message;
import com.cli.chat.common.MessageType;

class RecentMessagesTest {

    private static Message chat(String body) {
        return new Message(MessageType.BROADCAST, "alice", null, body, 0L);
    }

    private static List<String> bodies(List<Message> messages) {
        return messages.stream().map(Message::body).toList();
    }

    @Test
    void keepsMessagesInArrivalOrder() {
        RecentMessages recent = new RecentMessages(5);
        recent.add(chat("first"));
        recent.add(chat("second"));

        assertEquals(List.of("first", "second"), bodies(recent.last(5)));
    }

    @Test
    void evictsTheOldestOnceFull() {
        RecentMessages recent = new RecentMessages(3);
        for (int i = 0; i < 5; i++) {
            recent.add(chat("m" + i));
        }

        assertEquals(3, recent.size());
        assertEquals(List.of("m2", "m3", "m4"), bodies(recent.last(10)));
    }

    @Test
    void lastReturnsTheNewestSlice() {
        RecentMessages recent = new RecentMessages(10);
        for (int i = 0; i < 6; i++) {
            recent.add(chat("m" + i));
        }

        assertEquals(List.of("m4", "m5"), bodies(recent.last(2)));
    }

    @Test
    void lastIsEmptyForAnEmptyBuffer() {
        assertTrue(new RecentMessages(4).last(10).isEmpty());
    }

    @Test
    void lastReturnsASnapshotThatDoesNotChangeUnderTheCaller() {
        RecentMessages recent = new RecentMessages(2);
        recent.add(chat("first"));

        List<Message> snapshot = recent.last(2);
        recent.add(chat("second"));
        recent.add(chat("third"));

        assertEquals(List.of("first"), bodies(snapshot));
    }

    @Test
    void capacityMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new RecentMessages(0));
    }

    @Test
    void concurrentWritersNeverExceedTheCapacity() throws Exception {
        final int threads = 8;
        final int perThread = 500;
        RecentMessages recent = new RecentMessages(50);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier lineUp = new CyclicBarrier(threads);
        try {
            List<Future<?>> writers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int id = t;
                Callable<Void> writer = () -> {
                    lineUp.await();
                    for (int i = 0; i < perThread; i++) {
                        recent.add(chat(id + "-" + i));
                        recent.last(20);
                    }
                    return null;
                };
                writers.add(pool.submit(writer));
            }
            for (Future<?> writer : writers) {
                writer.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(50, recent.size());
        assertEquals(20, recent.last(20).size());
    }
}
