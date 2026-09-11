package com.cli.chat.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.cli.chat.common.Protocol;
import com.cli.chat.common.exception.ProtocolException;
import com.cli.chat.common.exception.TlsException;

class TlsSocketFactoryTest {

    private static TestKeystore keystore;

    @BeforeAll
    static void createKeystore() throws Exception {
        keystore = TestKeystore.create();
    }

    @AfterAll
    static void deleteKeystore() throws Exception {
        keystore.close();
    }

    private static TlsSocketFactory factory() throws TlsException {
        return TlsSocketFactory.fromKeystore(keystore.path(), keystore.password());
    }

    @Test
    void theListeningSocketIsATlsSocketOnModernProtocolsOnly() throws Exception {
        try (ServerSocket server = factory().createServerSocket(0)) {
            SSLServerSocket tls = assertInstanceOf(SSLServerSocket.class, server);

            List<String> protocols = List.of(tls.getEnabledProtocols());
            assertEquals(List.of("TLSv1.3", "TLSv1.2"), protocols);
            assertFalse(protocols.contains("TLSv1"), "old protocols should be off");
            assertTrue(server.getLocalPort() > 0);
        }
    }

    @Test
    void aWrongKeystorePasswordIsReported() {
        TlsException rejected = assertThrows(TlsException.class,
                () -> TlsSocketFactory.fromKeystore(keystore.path(), "not the password"));

        assertTrue(rejected.getMessage().contains("keystore"), "the failure should name what could not be loaded");
        assertTrue(rejected.getCause() != null, "the underlying failure should be kept");
    }

    @Test
    void aMissingKeystoreIsReported() {
        assertThrows(TlsException.class,
                () -> TlsSocketFactory.fromKeystore("no/such/keystore.p12", "changeit"));
    }

    @Test
    void aTrustingClientCompletesTheHandshakeAndExchangesALine() throws Exception {
        try (ServerSocket server = factory().createServerSocket(0)) {
            Thread greeter = new Thread(() -> {
                try (Socket accepted = server.accept();
                     PrintWriter out = new PrintWriter(accepted.getOutputStream(), true)) {
                    out.println("hello over tls");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            greeter.setDaemon(true);
            greeter.start();

            try (SSLSocket client = (SSLSocket) keystore.trustingClient().getSocketFactory()
                    .createSocket("localhost", server.getLocalPort());
                 BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()))) {

                client.startHandshake();

                assertTrue(client.getSession().getProtocol().startsWith("TLS"),
                        "the session should be negotiated over TLS");
                assertEquals("hello over tls", in.readLine());
            }
        }
    }

    private static boolean readsAProtocolLine(BufferedReader in) {
        try {
            String line = in.readLine();
            return line != null && Protocol.decode(line).type() != null;
        } catch (IOException | ProtocolException refused) {
            return false;
        }
    }

    @Test
    void aPlainClientCannotTalkToTheTlsPort() throws Exception {
        try (ServerSocket server = factory().createServerSocket(0)) {
            Thread accepter = new Thread(() -> {
                try (Socket accepted = server.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(accepted.getInputStream()))) {
                    in.readLine();
                } catch (IOException expected) {
                    return;
                }
            });
            accepter.setDaemon(true);
            accepter.start();

            try (Socket plain = new PlainSocketFactory().createSocket("localhost", server.getLocalPort());
                 BufferedReader in = new BufferedReader(new InputStreamReader(plain.getInputStream()))) {

                plain.setSoTimeout(3000);
                PrintWriter out = new PrintWriter(plain.getOutputStream(), true);
                out.println("plain text on a tls port");

                assertFalse(readsAProtocolLine(in), "a plain client must not get a usable line from a TLS port");
            }
        }
    }
}
