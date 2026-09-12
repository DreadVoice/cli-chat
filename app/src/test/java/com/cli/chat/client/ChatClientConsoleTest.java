package com.cli.chat.client;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

import com.cli.chat.common.Message;
import com.cli.chat.common.MessageType;

class ChatClientConsoleTest {

    private static LineReader readerOver(String typed) throws Exception {
        PipedOutputStream typing = new PipedOutputStream();
        InputStream in = new PipedInputStream(typing, 8192);
        typing.write(typed.getBytes(UTF_8));
        typing.flush();
        if (typed.isEmpty()) {
            typing.close();
        }
        Terminal terminal = TerminalBuilder.builder()
                .streams(in, new ByteArrayOutputStream())
                .dumb(true)
                .build();
        return ChatClient.lineReader(terminal);
    }

    @Test
    void aTypedLineComesBackAsItWasTyped() throws Exception {
        assertEquals("hello everyone", readerOver("hello everyone\n").readLine("> "));
        assertEquals("watch out !danger", readerOver("watch out !danger\n").readLine("> "),
                "chat text must reach the server exactly as typed");
    }

    @Test
    void theEndOfInputIsSignalled() throws Exception {
        LineReader console = readerOver("");

        assertThrows(EndOfFileException.class, () -> console.readLine("> "));
    }

    @Test
    void anIncomingMessagePrintsAboveTheLineBeingTyped() throws Exception {
        PipedOutputStream typing = new PipedOutputStream();
        InputStream in = new PipedInputStream(typing, 8192);
        ByteArrayOutputStream screen = new ByteArrayOutputStream();

        Terminal terminal = TerminalBuilder.builder()
                .streams(in, screen)
                .system(false)
                .type("xterm")
                .build();
        terminal.setSize(new Size(80, 24));
        LineReader console = ChatClient.lineReader(terminal);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<String> typed = pool.submit(() -> console.readLine("> "));
        try {
            typing.write("half typed".getBytes(UTF_8));
            typing.flush();
            waitFor(() -> screen.toString(UTF_8).contains("half typed"), "the typing should be echoed");

            int beforeMessage = screen.size();
            ChatClient.render(Message.broadcast("bob", "hello"), console);
            waitFor(() -> screen.toString(UTF_8).substring(beforeMessage).contains("[bob] hello"),
                    "the message should reach the screen");

            waitFor(() -> screen.toString(UTF_8).substring(beforeMessage).contains("half typed"),
                    "the half-typed line should be drawn again under the message");

            String after = screen.toString(UTF_8).substring(beforeMessage);
            assertTrue(after.indexOf("[bob] hello") < after.lastIndexOf("half typed"),
                    "the message belongs above the line being typed");

            typing.write("\n".getBytes(UTF_8));
            typing.flush();
            assertEquals("half typed", typed.get(3, TimeUnit.SECONDS), "nothing typed should be lost");
        } finally {
            pool.shutdownNow();
            terminal.close();
        }
    }

    @Test
    void aMessageArrivingBeforeAnyTypingStillPrints() throws Exception {
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        PipedOutputStream typing = new PipedOutputStream();

        Terminal terminal = TerminalBuilder.builder()
                .streams(new PipedInputStream(typing, 8192), screen)
                .system(false)
                .type("xterm")
                .build();
        terminal.setSize(new Size(80, 24));

        ChatClient.render(Message.system("bob joined"), ChatClient.lineReader(terminal));

        waitFor(() -> screen.toString(UTF_8).contains("*** bob joined ***"),
                "a message with nobody typing should still reach the screen");
        terminal.close();
    }

    private static void waitFor(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), what);
    }

    @Test
    void broadcastsAndPrivateDeliveriesShowTheirSender() {
        assertEquals("[alice] hello",
                ChatClient.line(new Message(MessageType.BROADCAST, "alice", null, "hello", 0L)));
        assertEquals("[alice] just for you",
                ChatClient.line(new Message(MessageType.PRIVATE_DELIVERY, "alice", "bob", "just for you", 0L)));
    }

    @Test
    void noticesRostersAndErrorsAreMarkedDifferently() {
        assertEquals("*** bob joined ***", ChatClient.line(Message.system("bob joined")));
        assertEquals("--- online: alice, bob ---", ChatClient.line(Message.userList(java.util.List.of("alice", "bob"))));
        assertEquals("!!! nope", ChatClient.line(Message.error("nope")));
    }

    @Test
    void anythingElseFallsBackToTheBody() {
        assertEquals("welcome, alice", ChatClient.line(Message.loginOk("alice")));
    }
}
