package com.cli.chat.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ClientRegistryTest {

    private static ClientHandler handler() {
        return new ClientHandler(null, null);
    }

    @Test
    void claimingAFreeNameSucceedsOnlyOnce() {
        ClientRegistry registry = new ClientRegistry();
        ClientHandler first = handler();

        assertTrue(registry.addIfAbsent("alice", first));
        assertEquals(false, registry.addIfAbsent("alice", handler()),
                "a second claim on the same name must be refused");
        assertEquals(first, registry.find("alice").orElseThrow(),
                "the loser must not displace the holder");
    }

    @Test
    void removingOnlyReleasesTheNameForItsCurrentHolder() {
        ClientRegistry registry = new ClientRegistry();
        ClientHandler holder = handler();
        registry.addIfAbsent("alice", holder);

        assertEquals(false, registry.remove("alice", handler()),
                "a stale handler must not evict the current holder");
        assertTrue(registry.find("alice").isPresent());

        assertTrue(registry.remove("alice", holder));
        assertTrue(registry.find("alice").isEmpty());
    }

    @Test
    void onlineUsersIsASortedSnapshotThatDoesNotTrackLaterChanges() {
        ClientRegistry registry = new ClientRegistry();
        registry.addIfAbsent("carol", handler());
        registry.addIfAbsent("alice", handler());
        registry.addIfAbsent("bob", handler());

        List<String> snapshot = registry.onlineUsers();
        assertEquals(List.of("alice", "bob", "carol"), snapshot);

        registry.addIfAbsent("dave", handler());
        assertEquals(List.of("alice", "bob", "carol"), snapshot, "the snapshot must not change under us");
        assertEquals(List.of("alice", "bob", "carol", "dave"), registry.onlineUsers());
    }


    @Test
    void concurrentClaimsOfTheSameNameLeaveExactlyOneWinner() throws Exception {
        final int threads = Math.max(4, Runtime.getRuntime().availableProcessors());
        final int rounds = 2000;

        ClientRegistry registry = new ClientRegistry();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier lineUp = new CyclicBarrier(threads);
        AtomicInteger round = new AtomicInteger();
        AtomicInteger winners = new AtomicInteger();

        try {
            for (int r = 0; r < rounds; r++) {
                final String name = "racer-" + r;
                round.set(r);
                winners.set(0);

                List<Future<?>> claims = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    claims.add(pool.submit(() -> {
                        lineUp.await();                       
                        if (registry.addIfAbsent(name, handler())) {
                            winners.incrementAndGet();
                        }
                        return null;
                    }));
                }
                for (Future<?> claim : claims) {
                    claim.get(15, TimeUnit.SECONDS);
                }

                assertEquals(1, winners.get(),
                        "round " + r + ": exactly one thread may claim '" + name + "'");
                assertEquals(r + 1, registry.size(), "every round must add exactly one entry");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
