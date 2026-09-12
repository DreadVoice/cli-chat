package com.cli.chat.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;

import javax.net.ssl.SSLSocket;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.cli.chat.common.Message;
import com.cli.chat.common.MessageType;
import com.cli.chat.common.Protocol;
import com.cli.chat.common.exception.TlsException;
import com.cli.chat.server.ChatServer;
import com.cli.chat.server.ServerConfig;

class TlsIntegrationTest {

    private static TestKeystore keystore;
    private static TestKeystore stranger;

    private ChatServer server;

    @BeforeAll
    static void createKeystores() throws Exception {
        keystore = TestKeystore.create();
        stranger = TestKeystore.create();
    }

    @AfterAll
    static void deleteKeystores() throws Exception {
        keystore.close();
        stranger.close();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    private int startServer(TestKeystore certificates) throws Exception {
        server = new ChatServer(ServerConfig.onPort(0)
                .withSockets(TlsSocketFactory.fromKeystore(certificates.path(), certificates.password())));
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
        return server.getPort();
    }

    @Test
    void aClientHoldingTheTruststoreChatsOverTls() throws Exception {
        int port = startServer(keystore);
        SocketFactory client = TlsSocketFactory.fromTruststore(keystore.truststorePath(), keystore.password());

        try (Socket socket = client.createSocket("localhost", port);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            socket.setSoTimeout(4000);

            assertTrue(((SSLSocket) socket).getSession().getProtocol().startsWith("TLS"));
            assertEquals(MessageType.SYSTEM, Protocol.decode(in.readLine()).type());

            out.println("alice");
            out.println(Protocol.encode(new Message(MessageType.USER_LIST, "alice", null, null, 0L)));

            assertEquals("alice", Protocol.decode(in.readLine()).body());
        }
    }

    @Test
    void aClientWithTheWrongTruststoreIsRefused() throws Exception {
        int port = startServer(keystore);
        SocketFactory client = TlsSocketFactory.fromTruststore(stranger.truststorePath(), stranger.password());

        assertThrows(IOException.class, () -> client.createSocket("localhost", port),
                "an unknown certificate must not be accepted");
    }

    @Test
    void aCertificateForAnotherHostIsRefused() throws Exception {
        try (TestKeystore elsewhere = TestKeystore.create("CN=elsewhere", "SAN=dns:elsewhere")) {
            int port = startServer(elsewhere);
            SocketFactory client = TlsSocketFactory.fromTruststore(
                    elsewhere.truststorePath(), elsewhere.password());

            assertThrows(IOException.class, () -> client.createSocket("localhost", port),
                    "a trusted certificate for the wrong host must not be accepted");
        }
    }

    @Test
    void anInsecureClientAcceptsAnUntrustedCertificate() throws Exception {
        int port = startServer(keystore);
        SocketFactory client = TlsSocketFactory.insecure();

        try (Socket socket = client.createSocket("localhost", port);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            socket.setSoTimeout(4000);

            assertTrue(((SSLSocket) socket).getSession().getProtocol().startsWith("TLS"),
                    "the fallback is still encrypted, only unchecked");
            assertEquals(MessageType.SYSTEM, Protocol.decode(in.readLine()).type());
        }
    }

    @Test
    void aMissingTruststoreIsReported() {
        assertThrows(TlsException.class,
                () -> TlsSocketFactory.fromTruststore("no/such/truststore.p12", "changeit"));
    }

    @Test
    void aPlainServerAndATlsClientDoNotConnect() throws Exception {
        try (ServerSocket plain = new PlainSocketFactory().createServerSocket(0)) {
            Thread accepter = new Thread(() -> {
                try (Socket accepted = plain.accept()) {
                    accepted.getInputStream().read();
                } catch (IOException expected) {
                    return;
                }
            });
            accepter.setDaemon(true);
            accepter.start();

            SocketFactory client = TlsSocketFactory.fromTruststore(
                    keystore.truststorePath(), keystore.password());

            assertThrows(IOException.class, () -> client.createSocket("localhost", plain.getLocalPort()),
                    "a TLS client must not fall back to plain text");
        }
    }
}
