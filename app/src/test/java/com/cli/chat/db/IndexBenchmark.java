package com.cli.chat.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.cli.chat.common.Message;
import com.cli.chat.common.MessageType;

public class IndexBenchmark {

    private static final int ROWS = 200_000;
    private static final int REPEATS = 50;
    private static final int LIMIT = 50;

    private static final String RECENT =
            "SELECT type, sender, recipient, body, timestamp FROM messages "
            + "ORDER BY timestamp DESC, id DESC LIMIT " + LIMIT;
    private static final String RECENT_FOR =
            "SELECT type, sender, recipient, body, timestamp FROM messages "
            + "WHERE recipient = 'user-500' ORDER BY timestamp DESC, id DESC LIMIT " + LIMIT;

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "index-benchmark.db";
        Database database = Database.file(path);
        database.initialise();

        seed(new SqliteMessageRepository(database));

        System.out.printf("%-14s %-12s %10s %10s%n", "query", "indices", "median", "plan");
        for (String query : new String[]{RECENT, RECENT_FOR}) {
            String label = query.contains("WHERE") ? "recentFor" : "recent";

            dropIndices(database);
            report(database, label, "dropped", query);

            createIndices(database);
            report(database, label, "present", query);
        }
    }

    private static void seed(MessageRepository repository) throws Exception {
        List<Message> batch = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            String recipient = i % 2 == 0 ? null : "user-" + (i % 1000);
            batch.add(new Message(MessageType.BROADCAST, "sender-" + (i % 50), recipient, "body " + i, i));
            if (batch.size() == 1000) {
                repository.saveAll(batch);
                batch.clear();
            }
        }
        repository.saveAll(batch);
    }

    private static void dropIndices(Database database) throws Exception {
        execute(database,
                "DROP INDEX IF EXISTS idx_messages_recent",
                "DROP INDEX IF EXISTS idx_messages_recipient_recent");
    }

    private static void createIndices(Database database) throws Exception {
        execute(database,
                "CREATE INDEX IF NOT EXISTS idx_messages_recent ON messages (timestamp DESC, id DESC)",
                "CREATE INDEX IF NOT EXISTS idx_messages_recipient_recent "
                        + "ON messages (recipient, timestamp DESC, id DESC)");
    }

    private static void execute(Database database, String... statements) throws Exception {
        try (Connection c = database.open();
             Statement s = c.createStatement()) {
            for (String sql : statements) {
                s.execute(sql);
            }
        }
    }

    private static void report(Database database, String label, String indices, String query)
            throws Exception {
        long[] timings = new long[REPEATS];
        for (int i = 0; i < REPEATS; i++) {
            long start = System.nanoTime();
            run(database, query);
            timings[i] = (System.nanoTime() - start) / 1_000_000;
        }
        java.util.Arrays.sort(timings);
        System.out.printf("%-14s %-12s %7d ms   %s%n", label, indices, timings[REPEATS / 2],
                plan(database, query));
    }

    private static void run(Database database, String query) throws Exception {
        try (Connection c = database.open();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(query)) {
            while (rs.next()) {
                rs.getString("body");
            }
        }
    }

    private static String plan(Database database, String query) throws Exception {
        try (Connection c = database.open();
             PreparedStatement s = c.prepareStatement("EXPLAIN QUERY PLAN " + query);
             ResultSet rs = s.executeQuery()) {
            return rs.next() ? rs.getString("detail") : "";
        }
    }
}
