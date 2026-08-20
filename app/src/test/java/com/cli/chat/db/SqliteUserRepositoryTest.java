package com.cli.chat.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.cli.chat.common.User;
import com.cli.chat.common.exception.StorageException;
import com.cli.chat.common.exception.UsernameTakenException;

class SqliteUserRepositoryTest {

    @TempDir
    Path dir;

    private UserRepository users;

    @BeforeEach
    void createRepository() throws StorageException {
        Database database = new Database(dir.resolve("chat.db").toString());
        database.initialise();
        users = new SqliteUserRepository(database);
    }

    @Test
    void createdUserIsReadBackWithItsGeneratedId() throws Exception {
        User created = users.create("alice", "hash");

        assertNotEquals(0L, created.id(), "the row id must come back from the insert");
        assertEquals("alice", created.username());

        User found = users.findByUsername("alice").orElseThrow();
        assertEquals(created, found);
    }

    @Test
    void createdAtIsRecorded() throws Exception {
        long before = System.currentTimeMillis();
        User created = users.create("alice", "hash");

        assertTrue(created.createdAt() >= before, "createdAt must be stamped at insert time");
        assertEquals(created.createdAt(), users.findByUsername("alice").orElseThrow().createdAt());
    }

    @Test
    void duplicateUsernameIsReportedAsUsernameTaken() throws Exception {
        users.create("alice", "hash");

        UsernameTakenException e =
                assertThrows(UsernameTakenException.class, () -> users.create("alice", "other"));
        assertEquals("alice", e.username());
        assertEquals("hash", users.findByUsername("alice").orElseThrow().passwordHash(),
                "the failed insert must not overwrite the existing row");
    }

    @Test
    void unknownUsernameIsEmpty() throws Exception {
        assertTrue(users.findByUsername("nobody").isEmpty());
        assertFalse(users.exists("nobody"));
    }

    @Test
    void existsReflectsCreatedUsers() throws Exception {
        users.create("alice", "hash");

        assertTrue(users.exists("alice"));
        assertFalse(users.exists("bob"));
    }

    @Test
    void usernamesAreCaseSensitive() throws Exception {
        users.create("alice", "hash");

        User other = users.create("Alice", "hash");
        assertNotEquals(0L, other.id());
        assertTrue(users.findByUsername("Alice").isPresent());
    }

    @Test
    void concurrentCreatesOfTheSameUsernameLeaveOneRow() throws Exception {
        final int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier lineUp = new CyclicBarrier(threads);

        try {
            List<Future<Boolean>> claims = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Boolean> claim = () -> {
                    lineUp.await();
                    try {
                        users.create("racer", "hash");
                        return true;
                    } catch (UsernameTakenException e) {
                        return false;
                    }
                };
                claims.add(pool.submit(claim));
            }

            int created = 0;
            for (Future<Boolean> claim : claims) {
                if (claim.get(15, TimeUnit.SECONDS)) {
                    created++;
                }
            }
            assertEquals(1, created, "the unique constraint must let exactly one insert through");
        } finally {
            pool.shutdownNow();
        }
    }
}
