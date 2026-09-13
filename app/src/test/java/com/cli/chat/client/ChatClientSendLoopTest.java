package com.cli.chat.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.junit.jupiter.api.Test;

import com.cli.chat.common.Message;
import com.cli.chat.common.MessageType;
import com.cli.chat.common.Protocol;
import com.cli.chat.common.exception.ProtocolException;

class ChatClientSendLoopTest {

    private final StringWriter sent = new StringWriter();

    private List<Message> run(Object... script) throws ProtocolException {
        Deque<Object> lines = new ArrayDeque<>(List.of(script));
        ChatClient.sendLoop(() -> {
            Object next = lines.poll();
            if (next instanceof RuntimeException interruption) {
                throw interruption;
            }
            return (String) next;
        }, new PrintWriter(sent, true), "alice", new StatusBar());

        List<Message> messages = new ArrayList<>();
        for (String line : sent.toString().split("\\R")) {
            if (!line.isBlank()) {
                messages.add(Protocol.decode(line));
            }
        }
        return messages;
    }

    @Test
    void aTypedLineIsSentAsAChatMessage() throws Exception {
        List<Message> messages = run("hello everyone", new EndOfFileException());

        assertEquals(MessageType.CHAT, messages.get(0).type());
        assertEquals("hello everyone", messages.get(0).body());
        assertEquals("alice", messages.get(0).sender());
    }

    @Test
    void ctrlCCancelsTheLineAndKeepsTheSessionOpen() throws Exception {
        List<Message> messages = run(new UserInterruptException("half typed"),
                "still here", new EndOfFileException());

        assertEquals(MessageType.CHAT, messages.get(0).type(), "the cancelled line must not be sent");
        assertEquals("still here", messages.get(0).body());
        assertEquals(MessageType.QUIT, messages.get(1).type());
        assertEquals(2, messages.size(), "an interrupt sends nothing of its own");
    }

    @Test
    void repeatedInterruptsNeverEndTheSession() throws Exception {
        List<Message> messages = run(new UserInterruptException(""), new UserInterruptException(""),
                new UserInterruptException(""), "still here", new EndOfFileException());

        assertEquals("still here", messages.get(0).body());
        assertEquals(MessageType.QUIT, messages.get(1).type());
    }

    @Test
    void ctrlDLeavesWithAGoodbye() throws Exception {
        List<Message> messages = run(new EndOfFileException());

        assertEquals(1, messages.size());
        assertEquals(MessageType.QUIT, messages.get(0).type(), "the server should be told we are leaving");
    }

    @Test
    void quitLeavesWithAGoodbyeToo() throws Exception {
        List<Message> messages = run("/quit", "never read");

        assertEquals(1, messages.size());
        assertEquals(MessageType.QUIT, messages.get(0).type());
    }

    @Test
    void whoAsksForTheRosterWithoutLeaving() throws Exception {
        List<Message> messages = run("/who", "and a message", new EndOfFileException());

        assertEquals(MessageType.USER_LIST, messages.get(0).type());
        assertEquals(MessageType.CHAT, messages.get(1).type());
        assertTrue(messages.get(2).type() == MessageType.QUIT);
    }
}
