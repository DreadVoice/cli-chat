package com.cli.chat.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cli.chat.common.Message;
import com.cli.chat.common.MessageType;
import com.cli.chat.common.Protocol;
import com.cli.chat.common.exception.ProtocolException;

class ChatServerTest {

    private static final Logger log = LoggerFactory.getLogger(ChatServerTest.class);

    private final AtomicReference<IOException> startFailure = new AtomicReference<>();

    private ChatServer server;
    private Thread serverThread;

    @BeforeEach
    void startServer() throws Exception {
        server = new ChatServer(0);
        serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (IOException e) {
                startFailure.set(e);
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        long deadline = System.currentTimeMillis() + 2000;
        while (server.getPort() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertNull(startFailure.get(), "server thread failed to start");
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

    @Test
    void serverBindsAndAcceptsConnections() throws Exception {
        try (TestClient alice = connect("alice")) {
            assertTrue(alice.socket.isConnected());
        }
    }

    @Test
    void malformedJsonGetsErrorButKeepsConnectionAlive() throws Exception {
        try (TestClient tester = connect("tester")) {

            tester.out.println("{not valid json");

            Message reply = tester.receive();
            assertEquals(MessageType.ERROR, reply.type());
            assertTrue(reply.body().contains("malformed"));

            tester.send(chat("still here"));

        }
    }

    @Test
    void validJsonWithNoTypeIsRejectedWithoutCrashing() throws Exception {
        try (TestClient tester = connect("tester")) {
            tester.out.println("{\"foo\":\"bar\"}");  

            Message reply = tester.receive();
            assertEquals(MessageType.ERROR, reply.type());

            tester.send(chat("recovered"));
        }
    }

    @Test
    void messageReachesOtherClients() throws Exception {
        try (TestClient alice = connect("alice");
             TestClient bob = connect("bob")) {

            alice.receive();                     // "bob joined"

            alice.send(chat("hello"));

            Message relayed = bob.receive();
            assertEquals(MessageType.BROADCAST, relayed.type());
            assertEquals("alice", relayed.sender());
            assertEquals("hello", relayed.body());
        }
    }

    @Test
    void senderDoesNotReceiveOwnMessage() throws Exception {
        try (TestClient alice = connect("alice");
             TestClient bob = connect("bob")) {

            alice.receive();                     

            alice.send(chat("hello"));
            bob.receive();                       

            assertThrows(SocketTimeoutException.class, alice.in::readLine,
                    "sender must not receive its own broadcast");
        }
    }

    @Test
    void joinIsAnnouncedToExistingClients() throws Exception {
        try (TestClient alice = connect("alice");
             TestClient bob = connect("bob")) {

            Message join = alice.receive();
            assertEquals(MessageType.SYSTEM, join.type());
            assertEquals("SERVER", join.sender());
            assertEquals("bob joined", join.body());
        }
    }

    @Test
    void quitIsAnnouncedToRemainingClients() throws Exception {
        try (TestClient alice = connect("alice")) {
            TestClient bob = connect("bob");
            alice.receive();                     

            bob.send(new Message(MessageType.QUIT, "bob", null, "", 0L));
            bob.close();

            Message left = alice.receive();
            assertEquals(MessageType.SYSTEM, left.type());
            assertEquals("bob left", left.body());
        }
    }

    @Test
    void malformedLineIsSkippedWithoutDroppingTheClient() throws Exception {
        try (TestClient alice = connect("alice");
             TestClient bob = connect("bob")) {

            alice.receive();                    

            alice.out.println("{not valid json");
            alice.send(chat("still here"));

            assertEquals("still here", bob.receive().body());
        }
    }

    @Test
    void unexpectedTypeIsRejectedWithAnError() throws Exception {
        try (TestClient alice = connect("alice")) {
            alice.send(new Message(MessageType.BROADCAST, "alice", null, "hello", 0L));

            Message reply = alice.receive();
            assertEquals(MessageType.ERROR, reply.type());
            assertTrue(reply.body().contains("BROADCAST"), "error should name the rejected type");
        }
    }

    @Test
    void registryTracksOnlineUsers() throws Exception {
        try (TestClient alice = connect("alice");
             TestClient bob = connect("bob")) {
            alice.receive();
        }
    }

    @Test
    void duplicateUsernameIsRejectedAndTheClientCanRetry() throws Exception {
        try (TestClient alice = connect("alice");
             TestClient impostor = new TestClient(server.getPort())) {

            impostor.in.readLine();              
            impostor.out.println("alice");       

            Message reply = impostor.receive();
            assertEquals(MessageType.ERROR, reply.type());
            assertTrue(reply.body().contains("alice"), "error should name the rejected username");

            impostor.in.readLine();              
            impostor.out.println("bob");

            Message join = alice.receive();
            assertEquals(MessageType.SYSTEM, join.type());
            assertEquals("bob joined", join.body(), "only the retry should be announced");
        }
    }

    @Test
    void twoClientsRacingTheSameUsernameLeaveExactlyOneWinner() throws Exception {
        final int racers = 8;

        try (TestClient observer = connect("observer")) {
            CyclicBarrier lineUp = new CyclicBarrier(racers);
            ExecutorService pool = Executors.newFixedThreadPool(racers);
            List<TestClient> connected = Collections.synchronizedList(new ArrayList<>());

            try {
                List<Future<Message>> outcomes = new ArrayList<>();
                for (int i = 0; i < racers; i++) {
                    
                    Callable<Message> racer = () -> {
                        TestClient c = new TestClient(server.getPort());
                        connected.add(c);
                        c.in.readLine();                 // name prompt
                        lineUp.await();                  
                        c.out.println("racer");
                        try {
                            return c.receive();
                        } catch (SocketTimeoutException accepted) {
                            return null;
                        }
                    };
                    outcomes.add(pool.submit(racer));
                }

                int winners = 0;
                for (Future<Message> outcome : outcomes) {
                    Message reply = outcome.get(15, TimeUnit.SECONDS);
                    if (reply == null) {
                        winners++;
                    } else {
                        assertEquals(MessageType.ERROR, reply.type(), "losers must be told the name is taken");
                        assertTrue(reply.body().contains("racer"), "error should name the rejected username");
                    }
                }
                assertEquals(1, winners, "exactly one client may hold a username");

                Message join = observer.receive();
                assertEquals(MessageType.SYSTEM, join.type());
                assertEquals("racer joined", join.body());
                assertThrows(SocketTimeoutException.class, observer.in::readLine,
                        "only the winner of the race may be announced");
            } finally {
                pool.shutdownNow();
                connected.forEach(TestClient::close);
            }
        }
    }

    @Test
    void userListRequestReturnsEveryoneOnlineIncludingTheRequester() throws Exception {
        try (TestClient alice = connect("alice");
             TestClient bob = connect("bob")) {

            alice.receive();                     // "bob joined"

            alice.send(new Message(MessageType.USER_LIST, "alice", null, null, 0L));

            Message roster = alice.receive();
            assertEquals(MessageType.USER_LIST, roster.type());
            assertEquals("SERVER", roster.sender());
            assertEquals("alice, bob", roster.body(), "roster is sorted and includes the requester");
        }
    }

    @Test
    void userListReflectsClientsLeaving() throws Exception {
        try (TestClient alice = connect("alice")) {
            TestClient bob = connect("bob");
            alice.receive();                     // "bob joined"

            bob.send(new Message(MessageType.QUIT, "bob", null, "", 0L));
            bob.close();
            alice.receive();                     // "bob left" — bob is off the registry by now

            alice.send(new Message(MessageType.USER_LIST, "alice", null, null, 0L));
            assertEquals("alice", alice.receive().body());
        }
    }

    @Test
    void userListIsNotBroadcastToOtherClients() throws Exception {
        try (TestClient alice = connect("alice");
             TestClient bob = connect("bob")) {

            alice.receive();                     // "bob joined"

            alice.send(new Message(MessageType.USER_LIST, "alice", null, null, 0L));
            alice.receive();                     // roster, to alice only

            assertThrows(SocketTimeoutException.class, bob.in::readLine,
                    "a roster request must not reach other clients");
        }
    }

    /** A client-to-server chat line, as the server currently expects it. */
    private static Message chat(String body) {
        return new Message(MessageType.CHAT, "ignored", null, body, 0L);
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
                log.warn("failed to close test client socket", e);
            }
        }
    }
}
