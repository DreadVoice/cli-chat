package com.cli.chat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UsernamesTest {

    @Test
    void ordinaryNamesAreAccepted() {
        assertTrue(Usernames.rejection("alice").isEmpty());
        assertTrue(Usernames.rejection("Bob_2").isEmpty());
        assertTrue(Usernames.rejection("carol.smith-1").isEmpty());
        assertTrue(Usernames.rejection("anon").isEmpty());
    }

    @Test
    void aMissingNameIsRejected() {
        assertTrue(Usernames.rejection(null).isPresent());
        assertTrue(Usernames.rejection("").isPresent());
        assertTrue(Usernames.rejection("   ").isPresent());
    }

    @Test
    void aNameLongerThanTheLimitIsRejected() {
        assertTrue(Usernames.rejection("a".repeat(Usernames.MAX_LENGTH)).isEmpty());
        assertTrue(Usernames.rejection("a".repeat(Usernames.MAX_LENGTH + 1)).isPresent());
    }

    @Test
    void aNameWithACommaIsRejected() {
        assertTrue(Usernames.rejection("bob,carol").isPresent(),
                "commas separate the names in a roster");
    }

    @Test
    void aNameWithWhitespaceIsRejected() {
        assertTrue(Usernames.rejection("bob carol").isPresent(),
                "whitespace separates a whisper target from its message");
        assertTrue(Usernames.rejection("bob\tcarol").isPresent());
    }

    @Test
    void theServerNameIsReserved() {
        assertTrue(Usernames.rejection("SERVER").isPresent());
        assertTrue(Usernames.rejection("server").isPresent());
        assertTrue(Usernames.rejection("Server").isPresent());
    }

    @Test
    void aRejectionSaysWhy() {
        assertEquals("a username can be at most 24 characters",
                Usernames.rejection("a".repeat(25)).orElseThrow());
        assertTrue(Usernames.rejection("bob!").orElseThrow().contains("letters, digits"));
    }
}
