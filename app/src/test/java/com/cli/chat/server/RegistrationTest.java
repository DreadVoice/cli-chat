package com.cli.chat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.favre.lib.crypto.bcrypt.BCrypt;

import com.cli.chat.common.Message;
import com.cli.chat.common.MessageType;
import com.cli.chat.common.Protocol;
import com.cli.chat.common.User;
import com.cli.chat.common.exception.ProtocolException;
import com.cli.chat.db.InMemoryDatabase;
import com.cli.chat.db.SqliteUserRepository;
import com.cli.chat.db.UserRepository;

class RegistrationTest {

    private static final String PASSWORD = "s3cret";

    private InMemoryDatabase database;
    private UserRepository users;
    private ChatServer server;

    @BeforeEach
    void startServer() throws Exception {
        database = InMemoryDatabase.create();
        users = new SqliteUserRepository(database.database());

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

    private static Message register(String username, String password) {
        return new Message(MessageType.REGISTER, username, null, password, 0L);
    }

    @Test
    void registeringStoresTheUserAndAuthenticatesTheClient() throws Exception {
        try (TestClient alice = connect()) {
            alice.send(register("alice", PASSWORD));

            Message reply = alice.receive();
            assertEquals(MessageType.LOGIN_OK, reply.type());
            assertEquals("alice", reply.recipient());

            alice.send(new Message(MessageType.USER_LIST, "alice", null, null, 0L));
            assertEquals("alice", alice.receive().body(), "the client should be online");
        }
    }

    @Test
    void thePasswordIsStoredAsABcryptHash() throws Exception {
        try (TestClient alice = connect()) {
            alice.send(register("alice", PASSWORD));
            alice.receive();

            User stored = users.findByUsername("alice").orElseThrow();
            assertNotEquals(PASSWORD, stored.passwordHash(), "the password must never be stored in the clear");
            assertTrue(stored.passwordHash().startsWith("$2a$12$"), "the hash should be bcrypt at cost 12");
            assertTrue(BCrypt.verifyer().verify(PASSWORD.toCharArray(), stored.passwordHash()).verified);
        }
    }

    @Test
    void registeringATakenUsernameIsRefusedAndLeavesTheClientUnauthenticated() throws Exception {
        try (TestClient alice = connect();
             TestClient impostor = connect()) {

            alice.send(register("alice", PASSWORD));
            alice.receive();

            impostor.send(register("alice", "another"));

            Message reply = impostor.receive();
            assertEquals(MessageType.LOGIN_FAIL, reply.type());
            assertTrue(reply.body().contains("alice"), "the failure should name the rejected username");

            impostor.send(new Message(MessageType.USER_LIST, "alice", null, null, 0L));
            assertEquals(MessageType.ERROR, impostor.receive().type(),
                    "a refused registration must not authenticate the client");
        }
    }

    @Test
    void aDuplicateRegisterLeavesTheOriginalCredentialsIntact() throws Exception {
        try (TestClient alice = connect()) {
            alice.send(register("alice", PASSWORD));
            assertEquals(MessageType.LOGIN_OK, alice.receive().type());
        }
        String stored = users.findByUsername("alice").orElseThrow().passwordHash();

        try (TestClient impostor = connect()) {
            impostor.send(register("alice", "another"));
            assertEquals(MessageType.LOGIN_FAIL, impostor.receive().type());
        }

        User after = users.findByUsername("alice").orElseThrow();
        assertEquals(stored, after.passwordHash(), "a refused registration must not touch the account");
        assertTrue(PasswordHasher.matches(PASSWORD, after.passwordHash()));
        assertFalse(PasswordHasher.matches("another", after.passwordHash()));
    }

    @Test
    void registeringANameSomeoneIsUsingOnlineIsRefused() throws Exception {
        try (TestClient squatter = connect();
             TestClient alice = connect()) {

            squatter.out.println("alice");

            alice.send(register("alice", PASSWORD));

            Message reply = alice.receive();
            assertEquals(MessageType.LOGIN_FAIL, reply.type());
            assertTrue(reply.body().contains("online"), "the failure should say the name is in use");
        }
    }

    @Test
    void usernamesThatDifferOnlyInCaseAreSeparateAccounts() throws Exception {
        try (TestClient lower = connect();
             TestClient upper = connect()) {

            lower.send(register("alice", PASSWORD));
            assertEquals(MessageType.LOGIN_OK, lower.receive().type());

            upper.send(register("Alice", PASSWORD));
            assertEquals(MessageType.LOGIN_OK, upper.receive().type(), "usernames are case-sensitive");
        }
    }

    @Test
    void registeringWithoutAPasswordIsRejected() throws Exception {
        try (TestClient alice = connect()) {
            alice.send(register("alice", null));

            Message reply = alice.receive();
            assertEquals(MessageType.ERROR, reply.type());
            assertTrue(users.findByUsername("alice").isEmpty(), "nothing should be stored");
        }
    }

    @Test
    void registeringOnAServerWithoutAUserStoreFails() throws Exception {
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
            alice.send(register("alice", PASSWORD));

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
