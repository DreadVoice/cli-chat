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

class CommandsTest {

    private ChatServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new ChatServer(0);
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
    void stopServer() {
        server.stop();
    }

    private TestClient connect(String name) throws IOException {
        TestClient c = new TestClient(server.getPort());
        c.in.readLine();
        c.out.println(name);
        return c;
    }

    private static Message command(String line) {
        return new Message(MessageType.COMMAND, "ignored", null, line, 0L);
    }

    private static Message chat(String body) {
        return new Message(MessageType.CHAT, "ignored", null, body, 0L);
    }

    @Test
    void helpListsEveryCommandInNameOrder() throws Exception {
        try (TestClient alice = connect("alice")) {
            alice.send(command("/help"));

            assertEquals("commands:", alice.receive().body());
            List<String> usages = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                usages.add(alice.receive().body());
            }
            assertEquals(List.of("/help", "/history [count]", "/kick <user>", "/list", "/shutdown",
                    "/whisper <user> <message>"), usages);
        }
    }

    @Test
    void aCommandWorksWithOrWithoutItsSlash() throws Exception {
        try (TestClient alice = connect("alice")) {
            alice.send(command("list"));
            assertEquals(MessageType.USER_LIST, alice.receive().type());

            alice.send(command("/list"));
            assertEquals(MessageType.USER_LIST, alice.receive().type());
        }
    }

    @Test
    void listReturnsEveryoneOnline() throws Exception {
        try (TestClient alice = connect("alice");
             TestClient bob = connect("bob")) {

            alice.receive();

            alice.send(command("/list"));

            Message roster = alice.receive();
            assertEquals(MessageType.USER_LIST, roster.type());
            assertEquals("alice, bob", roster.body());
        }
    }

    @Test
    void historyReplaysTheRecentMessages() throws Exception {
        try (TestClient alice = connect("alice");
             TestClient bob = connect("bob")) {

            alice.receive();

            bob.send(chat("first"));
            bob.send(chat("second"));
            alice.receive();
            alice.receive();

            alice.send(command("/history"));

            assertEquals("last 2 messages", alice.receive().body());
            assertEquals(List.of("first", "second"),
                    List.of(alice.receive().body(), alice.receive().body()));
        }
    }

    @Test
    void historyTakesACount() throws Exception {
        try (TestClient alice = connect("alice");
             TestClient bob = connect("bob")) {

            alice.receive();

            bob.send(chat("first"));
            bob.send(chat("second"));
            alice.receive();
            alice.receive();

            alice.send(command("/history 1"));

            assertEquals("last 1 messages", alice.receive().body());
            assertEquals("second", alice.receive().body(), "the newest message comes back");
        }
    }

    @Test
    void historyRejectsACountThatIsNotAPositiveNumber() throws Exception {
        try (TestClient alice = connect("alice")) {
            alice.send(command("/history soon"));
            assertTrue(alice.receive().body().contains("/history"), "the error should show the usage");

            alice.send(command("/history 0"));
            assertEquals(MessageType.ERROR, alice.receive().type());
        }
    }

    @Test
    void historyOnAQuietServerSaysSo() throws Exception {
        try (TestClient alice = connect("alice")) {
            alice.send(command("/history"));

            Message reply = alice.receive();
            assertEquals(MessageType.SYSTEM, reply.type());
            assertEquals("no messages yet", reply.body());
        }
    }

    @Test
    void whisperDeliversToTheTargetAndEchoesToTheSender() throws Exception {
        try (TestClient alice = connect("alice");
             TestClient bob = connect("bob");
             TestClient carol = connect("carol")) {

            alice.receive();
            alice.receive();
            bob.receive();

            alice.send(command("/whisper bob meet me at six"));

            Message delivered = bob.receive();
            assertEquals(MessageType.PRIVATE_DELIVERY, delivered.type());
            assertEquals("alice", delivered.sender());
            assertEquals("bob", delivered.recipient());
            assertEquals("meet me at six", delivered.body());

            assertEquals(MessageType.PRIVATE_DELIVERY, alice.receive().type());
            assertThrows(SocketTimeoutException.class, carol.in::readLine,
                    "a whisper must not reach anyone else");
        }
    }

    @Test
    void whisperNeedsATargetAndAMessage() throws Exception {
        try (TestClient alice = connect("alice")) {
            alice.send(command("/whisper bob"));

            Message reply = alice.receive();
            assertEquals(MessageType.ERROR, reply.type());
            assertTrue(reply.body().contains("/whisper <user> <message>"), "the error should show the usage");
        }
    }

    @Test
    void whisperToSomeoneWhoIsNotThereIsRejected() throws Exception {
        try (TestClient alice = connect("alice")) {
            alice.send(command("/whisper ghost hello"));

            Message reply = alice.receive();
            assertEquals(MessageType.ERROR, reply.type());
            assertTrue(reply.body().contains("ghost"), "the error should name the target");
        }
    }

    @Test
    void anUnknownCommandIsRejectedWithoutDroppingTheClient() throws Exception {
        try (TestClient alice = connect("alice")) {
            alice.send(command("/dance"));

            Message reply = alice.receive();
            assertEquals(MessageType.ERROR, reply.type());
            assertTrue(reply.body().contains("dance"), "the error should name the command");

            alice.send(command("/list"));
            assertEquals(MessageType.USER_LIST, alice.receive().type());
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
