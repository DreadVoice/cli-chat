package com.cli.chat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cli.chat.common.Message;
import com.cli.chat.common.MessageType;
import com.cli.chat.common.Protocol;
import com.cli.chat.common.exception.ProtocolException;
import com.cli.chat.db.InMemoryDatabase;
import com.cli.chat.db.MessageRepository;
import com.cli.chat.db.MessageWriter;
import com.cli.chat.db.SqliteMessageRepository;

class ChatHistoryTest {

    private InMemoryDatabase database;
    private MessageRepository messages;
    private MessageWriter writer;
    private ChatServer server;

    @BeforeEach
    void startServer() throws Exception {
        database = InMemoryDatabase.create();
        messages = new SqliteMessageRepository(database.database());
        writer = new MessageWriter(messages);
        writer.start();

        server = new ChatServer(0, writer, messages);
        Thread thread = new Thread(() -> {
            try {
                server.start();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        thread.setDaemon(true);
        thread.start();

        long deadline = System.currentTimeMillis() + 2000;
        while (server.getPort() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(server.getPort() > 0, "server failed to bind");
    }

    @AfterEach
    void stopServer() throws Exception {
        server.stop();
        database.close();
    }

    private TestClient connect(String name) throws IOException {
        TestClient c = new TestClient(server.getPort());
        c.in.readLine();
        c.out.println(name);
        return c;
    }

    private static Message chat(String body) {
        return new Message(MessageType.CHAT, "ignored", null, body, 0L);
    }

    private void waitForPersistence() throws Exception {
        assertTrue(writer.awaitEmpty(5000), "the writer should have drained");
        long deadline = System.currentTimeMillis() + 2000;
        while (messages.recent(100).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    @Test
    void chatMessagesArePersisted() throws Exception {
        try (TestClient alice = connect("alice")) {
            alice.send(chat("hello"));
            waitForPersistence();
        }

        List<Message> stored = messages.recent(10);
        assertEquals(1, stored.size());
        assertEquals(MessageType.BROADCAST, stored.get(0).type());
        assertEquals("alice", stored.get(0).sender());
        assertEquals("hello", stored.get(0).body());
    }

    @Test
    void aJoiningClientReceivesTheHistoryBeforeLiveTraffic() throws Exception {
        try (TestClient alice = connect("alice")) {
            alice.send(chat("first"));
            alice.send(chat("second"));
            waitForPersistence();

            try (TestClient bob = connect("bob")) {
                Message header = bob.receive();
                assertEquals(MessageType.SYSTEM, header.type());
                assertEquals("last 2 messages", header.body());

                assertEquals(List.of("first", "second"),
                        List.of(bob.receive().body(), bob.receive().body()));
            }
        }
    }

    @Test
    void historyIsCappedAtTheLastTwentyMessages() throws Exception {
        try (TestClient alice = connect("alice")) {
            for (int i = 0; i < 25; i++) {
                alice.send(chat("m" + i));
            }
            waitForPersistence();
            long deadline = System.currentTimeMillis() + 3000;
            while (messages.recent(100).size() < 25 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }

            try (TestClient bob = connect("bob")) {
                assertEquals("last 20 messages", bob.receive().body());

                List<String> replayed = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    replayed.add(bob.receive().body());
                }
                assertEquals("m5", replayed.get(0), "the oldest five must be dropped");
                assertEquals("m24", replayed.get(19), "the newest must come last");
            }
        }
    }

    @Test
    void chatBeforeAuthenticationIsNeitherBroadcastNorStored() throws Exception {
        try (TestClient alice = connect("alice");
             TestClient impostor = new TestClient(server.getPort())) {

            impostor.in.readLine();
            impostor.send(chat("i skipped the handshake"));

            assertEquals(MessageType.ERROR, impostor.receive().type());
            assertTrue(writer.awaitEmpty(1000), "the writer should have drained");
            assertTrue(messages.recent(10).isEmpty(), "an unauthenticated line must never be stored");
            assertThrows(SocketTimeoutException.class, alice.in::readLine,
                    "an unauthenticated line must not reach other clients");
        }
    }

    @Test
    void anEmptyHistorySendsNothing() throws Exception {
        try (TestClient alice = connect("alice")) {
            assertThrows(SocketTimeoutException.class, alice.in::readLine,
                    "a first client must not receive a history header");
        }
    }

    @Test
    void aServerWithoutHistoryStillAcceptsClients() throws Exception {
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

        try (TestClient alice = new TestClient(plain.getPort())) {
            alice.in.readLine();
            alice.out.println("alice");
            alice.send(chat("no storage here"));

            assertThrows(SocketTimeoutException.class, alice.in::readLine);
        } finally {
            plain.stop();
        }
    }

    @Test
    void aRestartedServerReplaysWhatWasAlreadyStored() throws Exception {
        messages.saveAll(List.of(
                new Message(MessageType.BROADCAST, "alice", null, "from before", 1000L),
                new Message(MessageType.BROADCAST, "bob", null, "also before", 2000L)));

        ChatServer restarted = new ChatServer(0, writer, messages);
        Thread thread = new Thread(() -> {
            try {
                restarted.start();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        thread.setDaemon(true);
        thread.start();

        long deadline = System.currentTimeMillis() + 2000;
        while (restarted.getPort() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        try (TestClient carol = new TestClient(restarted.getPort())) {
            carol.in.readLine();
            carol.out.println("carol");

            assertEquals("last 2 messages", carol.receive().body());
            assertEquals(List.of("from before", "also before"),
                    List.of(carol.receive().body(), carol.receive().body()));
        } finally {
            restarted.stop();
        }
    }

    @Test
    void historyComesFromTheCacheNotTheDatabase() throws Exception {
        messages.save(new Message(MessageType.BROADCAST, "ghost", null, "written behind the cache", 9000L));

        try (TestClient alice = connect("alice")) {
            assertThrows(SocketTimeoutException.class, alice.in::readLine,
                    "rows added after start-up are not visible until the cache sees them");
        }
    }

    private static class TestClient implements AutoCloseable {
        final Socket socket;
        final BufferedReader in;
        final PrintWriter out;

        TestClient(int port) throws IOException {
            socket = new Socket("localhost", port);
            socket.setSoTimeout(2000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);
        }

        void send(Message msg) throws ProtocolException {
            out.println(Protocol.encode(msg));
        }

        Message receive() throws IOException, ProtocolException {
            return Protocol.decode(in.readLine());
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
