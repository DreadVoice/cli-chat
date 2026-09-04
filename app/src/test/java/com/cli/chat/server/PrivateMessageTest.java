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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cli.chat.common.Message;
import com.cli.chat.common.MessageType;
import com.cli.chat.common.Protocol;
import com.cli.chat.common.exception.ProtocolException;
import com.cli.chat.db.InMemoryDatabase;
import com.cli.chat.db.SqliteUserRepository;
import com.cli.chat.db.UserRepository;

class PrivateMessageTest {

    private InMemoryDatabase database;
    private UserRepository users;
    private ChatServer server;

    @BeforeEach
    void startServer() throws Exception {
        database = InMemoryDatabase.create();
        users = new SqliteUserRepository(database.database());
        users.create("bob", PasswordHasher.hash("s3cret"));

        server = new ChatServer(0, null, null, users);
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

    private static Message privateTo(String recipient, String body) {
        return new Message(MessageType.PRIVATE, "alice", recipient, body, 0L);
    }

    @Test
    void aRegisteredUserWhoIsNotConnectedIsReportedAsOffline() throws Exception {
        try (TestClient alice = connect("alice")) {
            alice.send(privateTo("bob", "are you there"));

            Message reply = alice.receive();
            assertEquals(MessageType.ERROR, reply.type());
            assertTrue(reply.body().contains("bob"), "error should name the target");
            assertTrue(reply.body().contains("offline"), "a known account is offline, not missing");
        }
    }

    @Test
    void aNameWithNoAccountIsReportedAsUnknown() throws Exception {
        try (TestClient alice = connect("alice")) {
            alice.send(privateTo("ghost", "anyone there"));

            Message reply = alice.receive();
            assertEquals(MessageType.ERROR, reply.type());
            assertTrue(reply.body().contains("no such user"), "an unknown name is not an offline account");
            assertTrue(reply.body().contains("ghost"), "error should name the target");
        }
    }

    @Test
    void anOfflineTargetLeavesTheSenderConnected() throws Exception {
        try (TestClient alice = connect("alice")) {
            alice.send(privateTo("bob", "are you there"));
            alice.receive();

            alice.send(new Message(MessageType.USER_LIST, "alice", null, null, 0L));
            assertEquals("alice", alice.receive().body(), "the sender should still be online");
        }
    }

    @Test
    void aTargetThatComesOnlineIsReachedNormally() throws Exception {
        try (TestClient alice = connect("alice")) {
            alice.send(privateTo("bob", "first try"));
            assertEquals(MessageType.ERROR, alice.receive().type());

            try (TestClient bob = connect("bob")) {
                alice.receive();

                alice.send(privateTo("bob", "second try"));

                Message delivered = bob.receive();
                assertEquals(MessageType.PRIVATE_DELIVERY, delivered.type());
                assertEquals("second try", delivered.body());
                assertEquals(MessageType.PRIVATE_DELIVERY, alice.receive().type());
            }
        }
    }

    @Test
    void anOfflineTargetIsNotToldAnything() throws Exception {
        try (TestClient alice = connect("alice");
             TestClient carol = connect("carol")) {

            alice.receive();

            alice.send(privateTo("bob", "are you there"));
            assertEquals(MessageType.ERROR, alice.receive().type());

            assertThrows(SocketTimeoutException.class, carol.in::readLine,
                    "a refused private message must not reach anyone else");
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
