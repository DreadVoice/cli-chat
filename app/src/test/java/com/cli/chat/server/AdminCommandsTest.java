package com.cli.chat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

class AdminCommandsTest {

    private static final String PASSWORD = "s3cret";

    private InMemoryDatabase database;
    private UserRepository users;
    private ChatServer server;

    @BeforeEach
    void startServer() throws Exception {
        database = InMemoryDatabase.create();
        users = new SqliteUserRepository(database.database());
        users.create("root", PasswordHasher.hash(PASSWORD));

        server = new ChatServer(0, null, null, users, Set.of("root"));
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

    private TestClient claim(String name) throws IOException {
        TestClient c = new TestClient(server.getPort());
        c.in.readLine();
        c.out.println(name);
        return c;
    }

    private TestClient loginAsRoot() throws Exception {
        TestClient c = new TestClient(server.getPort());
        c.in.readLine();
        c.send(new Message(MessageType.LOGIN, "root", null, PASSWORD, 0L));
        assertEquals(MessageType.LOGIN_OK, c.receive().type());
        return c;
    }

    private static Message command(String line) {
        return new Message(MessageType.COMMAND, "ignored", null, line, 0L);
    }

    private static List<String> receiveBodies(TestClient client, int count) throws Exception {
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            bodies.add(client.receive().body());
        }
        return bodies;
    }

    @Test
    void anAdminCanKickAConnectedClient() throws Exception {
        try (TestClient root = loginAsRoot();
             TestClient alice = claim("alice")) {

            root.receive();

            root.send(command("/kick alice"));

            assertEquals("you were kicked by root", alice.receive().body());
            assertNull(alice.in.readLine(), "a kicked client should be disconnected");
            assertTrue(receiveBodies(root, 2).containsAll(List.of("kicked alice", "alice left")));
        }
    }

    @Test
    void aKickedClientLeavesTheRoster() throws Exception {
        try (TestClient root = loginAsRoot();
             TestClient alice = claim("alice")) {

            root.receive();

            root.send(command("/kick alice"));
            receiveBodies(root, 2);

            root.send(command("/list"));
            assertEquals("root", root.receive().body(), "a kicked client is off the registry");
        }
    }

    @Test
    void kickNeedsAName() throws Exception {
        try (TestClient root = loginAsRoot()) {
            root.send(command("/kick"));

            Message reply = root.receive();
            assertEquals(MessageType.ERROR, reply.type());
            assertTrue(reply.body().contains("/kick <user>"), "the error should show the usage");
        }
    }

    @Test
    void kickingSomeoneWhoIsNotOnlineIsRejected() throws Exception {
        try (TestClient root = loginAsRoot()) {
            root.send(command("/kick ghost"));

            Message reply = root.receive();
            assertEquals(MessageType.ERROR, reply.type());
            assertTrue(reply.body().contains("ghost"), "the error should name the target");
        }
    }

    @Test
    void anOrdinaryClientCannotKick() throws Exception {
        try (TestClient root = loginAsRoot();
             TestClient alice = claim("alice")) {

            root.receive();

            alice.send(command("/kick root"));

            Message reply = alice.receive();
            assertEquals(MessageType.ERROR, reply.type());
            assertTrue(reply.body().contains("admins"), "the refusal should say who may kick");

            alice.send(command("/list"));
            assertEquals("alice, root", alice.receive().body(), "nobody should have been kicked");
        }
    }

    @Test
    void claimingTheAdminNameWithTheNameLineDoesNotGrantAdmin() throws Exception {
        try (TestClient impostor = claim("root")) {
            impostor.send(command("/kick nobody"));

            Message reply = impostor.receive();
            assertEquals(MessageType.ERROR, reply.type());
            assertTrue(reply.body().contains("admins"), "admin comes from logging in, not from the name");
        }
    }

    @Test
    void anOrdinaryClientCannotShutTheServerDown() throws Exception {
        try (TestClient alice = claim("alice")) {
            alice.send(command("/shutdown"));

            Message reply = alice.receive();
            assertEquals(MessageType.ERROR, reply.type());
            assertTrue(reply.body().contains("admins"), "the refusal should say who may shut down");

            try (TestClient bob = claim("bob")) {
                assertTrue(bob.socket.isConnected(), "the server should still be accepting");
            }
        }
    }

    @Test
    void anAdminCanShutTheServerDown() throws Exception {
        int port = server.getPort();

        try (TestClient root = loginAsRoot();
             TestClient alice = claim("alice")) {

            root.receive();

            root.send(command("/shutdown"));

            assertEquals("server shutting down", alice.receive().body());
            assertTrue(receiveBodies(root, 1).contains("server shutting down"));
        }

        long deadline = System.currentTimeMillis() + 3000;
        boolean refused = false;
        while (System.currentTimeMillis() < deadline) {
            try (Socket probe = new Socket("localhost", port)) {
                Thread.sleep(50);
            } catch (IOException closed) {
                refused = true;
                break;
            }
        }
        assertTrue(refused, "a stopped server stops accepting connections");
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
