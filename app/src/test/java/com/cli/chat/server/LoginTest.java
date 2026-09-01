package com.cli.chat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

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

class LoginTest {

    private static final String PASSWORD = "s3cret";

    private InMemoryDatabase database;
    private UserRepository users;
    private ChatServer server;

    @BeforeEach
    void startServer() throws Exception {
        database = InMemoryDatabase.create();
        users = new SqliteUserRepository(database.database());
        users.create("alice", PasswordHasher.hash(PASSWORD));

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

    private TestClient connect() throws IOException {
        TestClient c = new TestClient(server.getPort());
        c.in.readLine();
        return c;
    }

    private static Message login(String username, String password) {
        return new Message(MessageType.LOGIN, username, null, password, 0L);
    }

    @Test
    void theRightPasswordAuthenticatesTheClient() throws Exception {
        try (TestClient alice = connect()) {
            alice.send(login("alice", PASSWORD));

            Message reply = alice.receive();
            assertEquals(MessageType.LOGIN_OK, reply.type());
            assertEquals("alice", reply.recipient());

            alice.send(new Message(MessageType.USER_LIST, "alice", null, null, 0L));
            assertEquals("alice", alice.receive().body(), "the client should be online");
        }
    }

    @Test
    void theWrongPasswordIsRejectedAndLeavesTheClientUnauthenticated() throws Exception {
        try (TestClient alice = connect()) {
            alice.send(login("alice", "guessing"));

            Message reply = alice.receive();
            assertEquals(MessageType.LOGIN_FAIL, reply.type());

            alice.send(new Message(MessageType.USER_LIST, "alice", null, null, 0L));
            assertEquals(MessageType.ERROR, alice.receive().type(),
                    "a refused login must not authenticate the client");
        }
    }

    @Test
    void anUnknownUserIsRefusedWithTheSameReasonAsAWrongPassword() throws Exception {
        try (TestClient stranger = connect();
             TestClient alice = connect()) {

            stranger.send(login("nobody", PASSWORD));
            Message unknown = stranger.receive();

            alice.send(login("alice", "guessing"));
            Message wrong = alice.receive();

            assertEquals(MessageType.LOGIN_FAIL, unknown.type());
            assertEquals(wrong.body(), unknown.body(),
                    "the failure must not reveal whether the username exists");
        }
    }

    @Test
    void loggingInTwiceAsTheSameUserIsRefused() throws Exception {
        try (TestClient alice = connect();
             TestClient elsewhere = connect()) {

            alice.send(login("alice", PASSWORD));
            assertEquals(MessageType.LOGIN_OK, alice.receive().type());

            elsewhere.send(login("alice", PASSWORD));

            Message reply = elsewhere.receive();
            assertEquals(MessageType.LOGIN_FAIL, reply.type());
            assertTrue(reply.body().contains("online"), "the failure should say the name is in use");
        }
    }

    @Test
    void loggingInWithoutAPasswordIsRejected() throws Exception {
        try (TestClient alice = connect()) {
            alice.send(login("alice", null));

            assertEquals(MessageType.ERROR, alice.receive().type());
        }
    }

    @Test
    void loggingInOnAServerWithoutAUserStoreFails() throws Exception {
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
            alice.send(login("alice", PASSWORD));

            assertEquals(MessageType.LOGIN_FAIL, alice.receive().type());
        } finally {
            plain.stop();
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
