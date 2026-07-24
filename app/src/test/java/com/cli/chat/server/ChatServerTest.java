package com.cli.chat.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class ChatServerTest {

    private ChatServer server;
    private Thread serverThread;

    @BeforeEach
    void startServer() throws Exception {
        server = new ChatServer(0);              // OS picks a free port
        serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (IOException ignored) {
                // stop() closes the socket; expected on teardown
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        // wait for the port to be bound rather than sleeping a fixed amount
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

    /** Connects, consumes the name prompt, sends a name. */
    private TestClient connect(String name) throws IOException {
        TestClient c = new TestClient(server.getPort());
        c.in.readLine();          // "Enter your name:"
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
    void messageReachesOtherClients() throws Exception {
        try (TestClient alice = connect("alice");
             TestClient bob = connect("bob")) {

            alice.in.readLine();                 // "*** bob joined ***"

            alice.out.println("hello");
            assertEquals("[alice] hello", bob.in.readLine());
        }
    }

    @Test
    void senderDoesNotReceiveOwnMessage() throws Exception {
        try (TestClient alice = connect("alice");
             TestClient bob = connect("bob")) {

            alice.in.readLine();                 // "*** bob joined ***"

            alice.out.println("hello");
            bob.in.readLine();                   // bob got it

            assertThrows(SocketTimeoutException.class, alice.in::readLine,
                    "sender must not receive its own broadcast");
        }
    }

    @Test
    void joinIsAnnouncedToExistingClients() throws Exception {
        try (TestClient alice = connect("alice");
             TestClient bob = connect("bob")) {

            assertEquals("*** bob joined ***", alice.in.readLine());
        }
    }

    @Test
    void quitIsAnnouncedToRemainingClients() throws Exception {
        try (TestClient alice = connect("alice")) {
            TestClient bob = connect("bob");
            alice.in.readLine();                 // "*** bob joined ***"

            bob.out.println("/quit");
            bob.close();

            assertEquals("*** bob left ***", alice.in.readLine());
        }
    }

    /** Socket + reader/writer bundle that closes cleanly. */
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

        @Override
        public void close() {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}