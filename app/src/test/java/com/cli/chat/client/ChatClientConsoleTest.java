package com.cli.chat.client;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
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
