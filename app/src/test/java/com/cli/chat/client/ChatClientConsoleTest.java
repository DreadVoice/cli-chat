package com.cli.chat.client;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.regex.Pattern;
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

    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d]*[ -/]*[@-~]");

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
            waitFor(() -> plain(screen).contains("half typed"), "the typing should be echoed");

            int beforeMessage = screen.size();
            ChatClient.render(Message.broadcast("bob", "hello"), console);
            waitFor(() -> plain(screen).substring(plainLength(beforeMessage, screen)).contains("[bob] hello"),
                    "the message should reach the screen");

            waitFor(() -> plain(screen).substring(plainLength(beforeMessage, screen)).contains("half typed"),
                    "the half-typed line should be drawn again under the message");

            String after = plain(screen).substring(plainLength(beforeMessage, screen));
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

    @Test
    void colourNeverChangesTheTextItself() {
        List<Message> samples = List.of(
                Message.broadcast("alice", "hello"),
                new Message(MessageType.PRIVATE_DELIVERY, "alice", "bob", "just for you", 0L),
                Message.system("bob joined"),
                Message.userList(List.of("alice", "bob")),
                Message.error("nope"),
                Message.loginOk("alice"),
                Message.loginFail("wrong username or password"));

        for (Message sample : samples) {
            assertEquals(ChatClient.line(sample), ChatClient.styled(sample).toString(),
                    "styling must not alter what the user reads");
        }
    }

    @Test
    void eachKindOfMessageCarriesItsOwnColour() {
        assertTrue(ChatClient.styled(Message.system("bob joined")).toAnsi().contains("36"),
                "notices should be cyan");
        assertTrue(ChatClient.styled(Message.userList(List.of("alice"))).toAnsi().contains("32"),
                "the roster should be green");
        assertTrue(ChatClient.styled(Message.error("nope")).toAnsi().contains("31"),
                "errors should be red");
        assertTrue(ChatClient.styled(Message.loginFail("wrong")).toAnsi().contains("31"),
                "a refused login reads as an error");
        assertTrue(ChatClient.styled(
                new Message(MessageType.PRIVATE_DELIVERY, "alice", "bob", "psst", 0L)).toAnsi().contains("35"),
                "private messages should be magenta");
    }

    @Test
    void onlyTheSenderIsStyledOnAChatMessage() {
        String ansi = ChatClient.styled(Message.broadcast("alice", "hello")).toAnsi();

        assertTrue(ansi.startsWith("\u001b["), "the sender prefix should carry the style");
        assertTrue(ansi.endsWith("hello"), "the message body should be left alone");
    }

    @Test
    void aTerminalWithoutColourGetsPlainText() throws Exception {
        PipedOutputStream typing = new PipedOutputStream();
        Terminal dumb = TerminalBuilder.builder()
                .streams(new PipedInputStream(typing, 8192), new ByteArrayOutputStream())
                .system(false)
                .type(Terminal.TYPE_DUMB)
                .build();

        String rendered = ChatClient.styled(Message.error("nope")).toAnsi(dumb);

        assertFalse(rendered.contains("\u001b"), "a terminal without colour should get plain text");
        assertEquals("!!! nope", rendered);
        dumb.close();
    }

    private static String plain(ByteArrayOutputStream screen) {
        return ANSI.matcher(screen.toString(UTF_8)).replaceAll("");
    }

    private static int plainLength(int rawLength, ByteArrayOutputStream screen) {
        String prefix = screen.toString(UTF_8).substring(0, rawLength);
        return ANSI.matcher(prefix).replaceAll("").length();
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
        assertEquals("--- online: alice, bob ---", ChatClient.line(Message.userList(List.of("alice", "bob"))));
        assertEquals("!!! nope", ChatClient.line(Message.error("nope")));
    }

    @Test
    void anythingElseFallsBackToTheBody() {
        assertEquals("welcome, alice", ChatClient.line(Message.loginOk("alice")));
    }
}
