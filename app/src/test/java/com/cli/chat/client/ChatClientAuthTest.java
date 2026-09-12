package com.cli.chat.client;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintWriter;
import java.net.Socket;

import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cli.chat.common.MessageType;
import com.cli.chat.db.SqliteUserRepository;
import com.cli.chat.db.TempDatabase;
import com.cli.chat.db.UserRepository;
import com.cli.chat.server.ChatServer;
import com.cli.chat.server.ServerConfig;

class ChatClientAuthTest {

    private TempDatabase database;
    private UserRepository users;
    private ChatServer server;

    @BeforeEach
    void startServer() throws Exception {
        database = TempDatabase.create();
        users = new SqliteUserRepository(database.database());

        server = new ChatServer(ServerConfig.onPort(0).withUsers(users));
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

    private String handshake(String typed, boolean register) throws Exception {
        PipedOutputStream typing = new PipedOutputStream();
        Terminal terminal = TerminalBuilder.builder()
                .streams(new PipedInputStream(typing, 8192), new ByteArrayOutputStream())
                .system(false)
                .dumb(true)
                .build();
        typing.write(typed.getBytes(UTF_8));
        typing.flush();
        if (typed.isEmpty()) {
            typing.close();
        }
        LineReader console = ChatClient.lineReader(terminal, null);

        try (Socket socket = new Socket("localhost", server.getPort());
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            socket.setSoTimeout(4000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            return ChatClient.handshake(in, out, console, register);
        } finally {
            terminal.close();
        }
    }

    @Test
    void registeringCreatesTheAccountAndSignsIn() throws Exception {
        assertEquals("alice", handshake("alice\ns3cret\n", true));

        assertTrue(users.findByUsername("alice").isPresent(), "the account should exist afterwards");
    }

    @Test
    void loggingInWithTheRightPasswordSignsIn() throws Exception {
        handshake("alice\ns3cret\n", true);

        assertEquals("alice", handshake("alice\ns3cret\n", false));
    }

    @Test
    void aWrongPasswordAsksAgain() throws Exception {
        handshake("alice\ns3cret\n", true);

        assertEquals("alice", handshake("alice\nguessing\nalice\ns3cret\n", false),
                "a refused password should bring the prompt back, not drop the client");
    }

    @Test
    void aBlankPasswordJoinsAsAGuest() throws Exception {
        assertEquals("carol", handshake("carol\n\n", false));

        assertTrue(users.findByUsername("carol").isEmpty(), "a guest has no account");
    }

    @Test
    void leavingThePromptGivesUp() throws Exception {
        assertNull(handshake("", false), "end of input at the prompt should not join");
    }

    @Test
    void theCredentialsCarryTheNameAndPassword() {
        assertEquals(MessageType.LOGIN, ChatClient.credentials(false, "alice", "s3cret").type());
        assertEquals(MessageType.REGISTER, ChatClient.credentials(true, "alice", "s3cret").type());
        assertEquals("alice", ChatClient.credentials(true, "alice", "s3cret").sender());
        assertEquals("s3cret", ChatClient.credentials(true, "alice", "s3cret").body());
    }
}
