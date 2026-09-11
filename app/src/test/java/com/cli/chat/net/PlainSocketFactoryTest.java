package com.cli.chat.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;

import org.junit.jupiter.api.Test;

class PlainSocketFactoryTest {

    private final SocketFactory sockets = new PlainSocketFactory();

    @Test
    void aServerSocketBindsAndReportsItsPort() throws Exception {
        try (ServerSocket server = sockets.createServerSocket(0)) {
            assertTrue(server.getLocalPort() > 0, "port 0 should bind to a free port");
            assertTrue(!server.isClosed());
        }
    }

    @Test
    void aSocketFromTheFactoryTalksToAServerSocketFromTheFactory() throws Exception {
        try (ServerSocket server = sockets.createServerSocket(0)) {
            Thread greeter = new Thread(() -> {
                try (Socket accepted = server.accept();
                     PrintWriter out = new PrintWriter(accepted.getOutputStream(), true)) {
                    out.println("hello from the server");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            greeter.setDaemon(true);
            greeter.start();

            try (Socket client = sockets.createSocket("localhost", server.getLocalPort());
                 BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()))) {

                assertEquals("hello from the server", in.readLine());
            }
        }
    }

    @Test
    void connectingToAClosedPortFails() throws Exception {
        int port;
        try (ServerSocket server = sockets.createServerSocket(0)) {
            port = server.getLocalPort();
        }

        assertThrows(ConnectException.class, () -> sockets.createSocket("localhost", port));
    }
}
