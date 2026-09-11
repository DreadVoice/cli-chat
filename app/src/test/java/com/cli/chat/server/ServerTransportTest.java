package com.cli.chat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.cli.chat.common.Message;
import com.cli.chat.common.MessageType;
import com.cli.chat.common.Protocol;
import com.cli.chat.net.PlainSocketFactory;
import com.cli.chat.net.SocketFactory;

class ServerTransportTest {

    private final CountingSocketFactory sockets = new CountingSocketFactory();

    private ChatServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    private void startServer() throws Exception {
        server = new ChatServer(0, null, null, null, Set.of(), sockets);
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

    @Test
    void theServerListensOnASocketFromTheFactory() throws Exception {
        startServer();

        assertEquals(1, sockets.serverSockets.get(), "the listening socket should come from the factory");
    }

    @Test
    void aClientConnectingThroughTheFactoryCanChat() throws Exception {
        startServer();

        try (Socket socket = sockets.createSocket("localhost", server.getPort());
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            socket.setSoTimeout(2000);

            in.readLine();
            out.println("alice");

            out.println(Protocol.encode(new Message(MessageType.USER_LIST, "alice", null, null, 0L)));
            assertEquals("alice", Protocol.decode(in.readLine()).body());
            assertEquals(1, sockets.clientSockets.get(), "the client socket should come from the factory too");
        }
    }

    private static class CountingSocketFactory implements SocketFactory {

        private final SocketFactory delegate = new PlainSocketFactory();
        private final AtomicInteger serverSockets = new AtomicInteger();
        private final AtomicInteger clientSockets = new AtomicInteger();

        @Override
        public ServerSocket createServerSocket(int port) throws IOException {
            serverSockets.incrementAndGet();
            return delegate.createServerSocket(port);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            clientSockets.incrementAndGet();
            return delegate.createSocket(host, port);
        }
    }
}
