package com.cli.chat.common;

import com.cli.chat.common.exception.ProtocolException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ProtocolTest {

    @Test
    @DisplayName("A typical chat message survives encode -> decode unchanged")
    void roundTripsChatMessage() throws ProtocolException {
        Message original = new Message(
                MessageType.CHAT, "alice", null, "hello", 1706000000000L);

        Message result = Protocol.decode(Protocol.encode(original));

        assertEquals(original, result);
    }

    @Test
    @DisplayName("A private message with a target survives the round trip")
    void roundTripsPrivateMessage() throws ProtocolException {
        Message original = new Message(
                MessageType.PRIVATE, "alice", "bob", "psst", 1706000000000L);

        Message result = Protocol.decode(Protocol.encode(original));

        assertEquals(original, result);
        assertEquals("bob", result.recipient());   
    }

    @ParameterizedTest
    @EnumSource(MessageType.class)
    @DisplayName("Every MessageType round-trips")
    void roundTripsEveryType(MessageType type) throws ProtocolException {
        Message original = new Message(type, "sender", "target", "body", 42L);

        Message result = Protocol.decode(Protocol.encode(original));

        assertEquals(original, result);
        assertEquals(type, result.type());
    }

    @Test
    @DisplayName("A null target survives as null, not the string \"null\"")
    void preservesNullTarget() throws ProtocolException {
        Message original = new Message(
                MessageType.BROADCAST, "server", null, "hi all", 1L);

        Message result = Protocol.decode(Protocol.encode(original));

        assertNull(result.recipient());
    }

    @Test
    @DisplayName("Special characters in the body don't break framing or content")
    void preservesSpecialCharactersInBody() throws ProtocolException {
        String tricky = "line one\nline two\ttabbed \"quoted\" {\"json\":true} \\backslash";
        Message original = new Message(
                MessageType.CHAT, "alice", null, tricky, 1L);

        String encoded = Protocol.encode(original);
        Message result = Protocol.decode(encoded);

        assertEquals(tricky, result.body());               
        assertEquals(1, encoded.lines().count());          
    }

    @Test
    @DisplayName("Malformed JSON is rejected, not silently accepted")
    void rejectsMalformedJson() {
        assertThrows(ProtocolException.class, () -> Protocol.decode("{not valid json"));
    }

    @Test
    @DisplayName("A decode failure does not echo the payload it failed on")
    void decodeFailureKeepsThePayloadOutOfTheException() {
        String secret = "my-private-message";

        ProtocolException e = assertThrows(ProtocolException.class,
                () -> Protocol.decode("{\"type\":\"CHAT\",\"body\":\"" + secret + "\""));

        for (Throwable t = e; t != null; t = t.getCause()) {
            assertFalse(String.valueOf(t.getMessage()).contains(secret),
                    "exception messages must not carry message bodies into the logs");
        }
    }
}