package com.cli.chat.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

class StatusBarTest {

    private static Terminal terminal(String type, ByteArrayOutputStream screen) throws Exception {
        PipedOutputStream typing = new PipedOutputStream();
        Terminal terminal = TerminalBuilder.builder()
                .streams(new PipedInputStream(typing, 8192), screen)
                .system(false)
                .type(type)
                .build();
        terminal.setSize(new Size(80, 24));
        return terminal;
    }

    @Test
    void aFreshBarKnowsNothingYet() throws Exception {
        try (Terminal terminal = terminal("xterm", new ByteArrayOutputStream())) {
            assertEquals("disconnected  not signed in  0 online", new StatusBar(terminal).text());
        }
    }

    @Test
    void signingInShowsTheNameAndTheConnection() throws Exception {
        try (Terminal terminal = terminal("xterm", new ByteArrayOutputStream())) {
            StatusBar bar = new StatusBar(terminal);

            bar.connected("alice");
            bar.online(3);

            assertEquals("connected  alice  3 online", bar.text());
        }
    }

    @Test
    void losingTheConnectionKeepsTheNameButSaysSo() throws Exception {
        try (Terminal terminal = terminal("xterm", new ByteArrayOutputStream())) {
            StatusBar bar = new StatusBar(terminal);
            bar.connected("alice");
            bar.online(2);

            bar.disconnected();

            assertEquals("disconnected  alice  2 online", bar.text());
        }
    }

    @Test
    void theCountComesFromTheRoster() {
        assertEquals(0, StatusBar.count(null));
        assertEquals(0, StatusBar.count("   "));
        assertEquals(1, StatusBar.count("alice"));
        assertEquals(2, StatusBar.count("alice, bob"));
        assertEquals(3, StatusBar.count("alice, bob, carol"));
    }

    @Test
    void aRosterAskedForByTheBarIsConsumedOnce() throws Exception {
        try (Terminal terminal = terminal("xterm", new ByteArrayOutputStream())) {
            StatusBar bar = new StatusBar(terminal);

            assertFalse(bar.consumeRefresh(), "a roster nobody asked for belongs to the user");

            bar.refreshRequested();
            assertTrue(bar.consumeRefresh(), "the bar's own request should not be printed");
            assertFalse(bar.consumeRefresh(), "only one reply per request");
        }
    }

    @Test
    void theBarIsDrawnOnATerminalThatSupportsIt() throws Exception {
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        try (Terminal terminal = terminal("xterm", screen)) {
            StatusBar bar = new StatusBar(terminal);

            bar.connected("alice");
            terminal.flush();

            long deadline = System.currentTimeMillis() + 3000;
            while (!screen.toString().contains("connected  alice") && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(screen.toString().contains("connected  alice  0 online"),
                    "the bar should reach a terminal that can draw it");
        }
    }

    @Test
    void aTerminalWithoutCursorAddressingIsLeftAlone() throws Exception {
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        try (Terminal terminal = terminal(Terminal.TYPE_DUMB, screen)) {
            StatusBar bar = new StatusBar(terminal);

            bar.connected("alice");
            bar.online(2);
            terminal.flush();
            Thread.sleep(200);

            assertFalse(screen.toString().contains("alice"), "a dumb terminal gets no status bar");
            assertEquals("connected  alice  2 online", bar.text(), "the state is still tracked");
        }
    }
}
