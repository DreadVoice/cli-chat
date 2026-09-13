package com.cli.chat.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StatusBarTest {

    @Test
    void aFreshBarKnowsNothingYet() {
        assertEquals("disconnected  not signed in  0 online", new StatusBar().text());
    }

    @Test
    void signingInShowsTheNameAndTheConnection() {
        StatusBar bar = new StatusBar();

        bar.connected("alice");
        bar.online(3);

        assertEquals("connected  alice  3 online", bar.text());
    }

    @Test
    void losingTheConnectionKeepsTheNameButSaysSo() {
        StatusBar bar = new StatusBar();
        bar.connected("alice");
        bar.online(2);

        bar.disconnected();

        assertEquals("disconnected  alice  2 online", bar.text());
    }

    @Test
    void thePromptCarriesTheNameAndTheCount() {
        StatusBar bar = new StatusBar();
        bar.connected("alice");
        bar.online(3);

        String prompt = bar.prompt();

        assertTrue(prompt.contains("[alice 3 online]"), "the prompt should say who and how many");
        assertTrue(prompt.endsWith("> ") || prompt.contains("> "), "it should still read as a prompt");
        assertTrue(prompt.contains("\u001b["), "the prompt should be styled");
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
    void aRosterTheUserAskedForIsNeverSwallowed() {
        StatusBar bar = new StatusBar();
        bar.refreshRequested();
        bar.rosterAskedFor();

        assertTrue(bar.consumeAsk(), "a /who reply belongs to the user");
        assertFalse(bar.consumeAsk(), "one reply per question");
    }

    @Test
    void aRosterAskedForByTheBarIsConsumedOnce() {
        StatusBar bar = new StatusBar();

        assertFalse(bar.consumeRefresh(), "a roster nobody asked for belongs to the user");

        bar.refreshRequested();
        assertTrue(bar.consumeRefresh(), "the bar's own request should not be printed");
        assertFalse(bar.consumeRefresh(), "only one reply per request");
    }
}
