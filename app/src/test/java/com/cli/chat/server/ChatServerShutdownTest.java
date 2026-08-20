package com.cli.chat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cli.chat.common.Message;
import com.cli.chat.common.MessageType;
import com.cli.chat.common.exception.StorageException;
import com.cli.chat.db.InMemoryDatabase;
import com.cli.chat.db.MessageRepository;
import com.cli.chat.db.MessageWriter;
import com.cli.chat.db.SqliteMessageRepository;

class ChatServerShutdownTest {

    private InMemoryDatabase database;
    private MessageRepository messages;
    private MessageWriter writer;
    private ChatServer server;
    private Thread serverThread;

    @BeforeEach
    void startServer() throws Exception {
        database = InMemoryDatabase.create();
        messages = new SqliteMessageRepository(database.database());
        writer = new MessageWriter(messages);
        writer.start();

        server = new ChatServer(0, writer);
        serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        long deadline = System.currentTimeMillis() + 2000;
        while (server.getPort() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(server.getPort() > 0, "server failed to bind");
    }

    @AfterEach
    void closeDatabase() throws Exception {
        server.stop();
        database.close();
    }

    private static Message chat(String body, long timestamp) {
        return new Message(MessageType.BROADCAST, "alice", null, body, timestamp);
    }

    @Test
    void stopDrainsTheQueuedMessagesBeforeReturning() throws Exception {
        for (int i = 0; i < 300; i++) {
            writer.submit(chat("m" + i, 1000L + i));
        }

        server.stop();

        assertEquals(300, messages.recent(1000).size(),
                "queued messages must be written before the server exits");
    }

    @Test
    void stopRejectsFurtherPersistence() throws Exception {
        server.stop();

        assertFalse(writer.submit(chat("too late", 1000L)));
    }

    @Test
    void stopDisconnectsConnectedClients() throws Exception {
        try (Socket socket = new Socket("localhost", server.getPort());
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            socket.setSoTimeout(5000);
            in.readLine();
            out.println("alice");

            long deadline = System.currentTimeMillis() + 2000;
            while (server.registry().onlineUsers().isEmpty()
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(List.of("alice"), server.registry().onlineUsers());

            server.stop();

            assertTrue(in.readLine() == null, "the client stream must end when the server stops");
        }
    }

    @Test
    void stopIsSafeToCallTwice() throws Exception {
        writer.submit(chat("once", 1000L));

        server.stop();
        server.stop();

        assertEquals(1, messages.recent(10).size());
    }

    @Test
    void stopWithoutAWriterStillShutsDown() throws Exception {
        ChatServer plain = new ChatServer(0);
        Thread thread = new Thread(() -> {
            try {
                plain.start();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        thread.setDaemon(true);
        thread.start();

        long deadline = System.currentTimeMillis() + 2000;
        while (plain.getPort() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        plain.stop();
        assertTrue(true, "stop must not throw when no writer is attached");
    }

    @Test
    void aFreshServerCanBeStoppedBeforeItStarts() throws StorageException {
        new ChatServer(0).stop();
    }
}
