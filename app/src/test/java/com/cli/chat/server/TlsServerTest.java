package com.cli.chat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Set;

import javax.net.ssl.SSLSocket;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cli.chat.common.Message;
import com.cli.chat.common.MessageType;
import com.cli.chat.common.Protocol;
import com.cli.chat.net.TestKeystore;
import com.cli.chat.net.TlsSocketFactory;

class TlsServerTest {

    private static TestKeystore keystore;

    private ChatServer server;

    @BeforeAll
    static void createKeystore() throws Exception {
        keystore = TestKeystore.create();
    }

    @AfterAll
    static void deleteKeystore() throws Exception {
        keystore.close();
    }

    @BeforeEach
    void startServer() throws Exception {
        server = new ChatServer(0, null, null, null, Set.of(),
                TlsSocketFactory.fromKeystore(keystore.path(), keystore.password()));
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

    private SSLSocket connect() throws Exception {
        SSLSocket socket = (SSLSocket) keystore.trustingClient().getSocketFactory()
                .createSocket("localhost", server.getPort());
        socket.setSoTimeout(4000);
        socket.startHandshake();
        return socket;
    }

    @Test
    void theHandshakeAndAChatRoundTripRunOverTls() throws Exception {
        try (SSLSocket socket = connect();
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

            assertTrue(socket.getSession().getProtocol().startsWith("TLS"));

            Message prompt = Protocol.decode(in.readLine());
            assertEquals(MessageType.SYSTEM, prompt.type());

            out.println("alice");
            out.println(Protocol.encode(new Message(MessageType.USER_LIST, "alice", null, null, 0L)));

            Message roster = Protocol.decode(in.readLine());
            assertEquals(MessageType.USER_LIST, roster.type());
            assertEquals("alice", roster.body());
        }
    }

    @Test
    void twoTlsClientsStillSeeEachOther() throws Exception {
        try (SSLSocket aliceSocket = connect();
             SSLSocket bobSocket = connect()) {

            BufferedReader aliceIn = new BufferedReader(new InputStreamReader(aliceSocket.getInputStream()));
            PrintWriter aliceOut = new PrintWriter(aliceSocket.getOutputStream(), true);
            BufferedReader bobIn = new BufferedReader(new InputStreamReader(bobSocket.getInputStream()));
            PrintWriter bobOut = new PrintWriter(bobSocket.getOutputStream(), true);

            aliceIn.readLine();
            aliceOut.println("alice");
            bobIn.readLine();
            bobOut.println("bob");

            assertEquals("bob joined", Protocol.decode(aliceIn.readLine()).body());

            aliceOut.println(Protocol.encode(new Message(MessageType.CHAT, "alice", null, "over tls", 0L)));

            Message relayed = Protocol.decode(bobIn.readLine());
            assertEquals(MessageType.BROADCAST, relayed.type());
            assertEquals("over tls", relayed.body());
        }
    }
}
