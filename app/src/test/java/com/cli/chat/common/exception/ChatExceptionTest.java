package com.cli.chat.common.exception;

import com.cli.chat.common.Protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ChatExceptionTest {

    @Test
    void everyChatFailureIsCatchableAsChatException() {
        assertInstanceOf(ChatException.class, new ProtocolException("bad line"));
        assertInstanceOf(ChatException.class, new AuthenticationException("bad password"));
        assertInstanceOf(AuthenticationException.class, new UsernameTakenException("alice"));
    }

    @Test
    void usernameTakenCarriesTheRejectedName() {
        UsernameTakenException e = new UsernameTakenException("alice");

        assertEquals("alice", e.username());
        assertEquals("username 'alice' is already taken", e.getMessage());
    }

    @Test
    void protocolExceptionKeepsTheUnderlyingCause() {
        ProtocolException e = assertThrows(ProtocolException.class,
                () -> Protocol.decode("{not valid json"));

        assertInstanceOf(ChatException.class, e);
        assertNotNull(e.getCause(), "the parser failure must not be swallowed");
        assertEquals("could not parse as JSON", e.getMessage());
    }
}
