package com.cli.chat.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.cli.chat.client.ChatClient.Options;

class ChatClientOptionsTest {

    @Test
    void noArgumentsMeanTheLocalServerInTheClear() {
        Options options = ChatClient.parse(new String[0]);

        assertEquals("localhost", options.host);
        assertEquals(5000, options.port);
        assertNull(options.truststore);
        assertFalse(options.insecure);
    }

    @Test
    void hostAndPortAreStillPositional() {
        Options options = ChatClient.parse(new String[] {"chat.example.com", "6000"});

        assertEquals("chat.example.com", options.host);
        assertEquals(6000, options.port);
    }

    @Test
    void aTruststoreCanBeGivenWithItsPassword() {
        Options options = ChatClient.parse(new String[] {
                "--truststore", "certs/trust.p12", "--truststore-password", "changeit"});

        assertEquals("certs/trust.p12", options.truststore);
        assertEquals("changeit", options.truststorePassword);
        assertFalse(options.insecure);
    }

    @Test
    void flagsAndPositionalArgumentsCanBeMixed() {
        Options options = ChatClient.parse(new String[] {"--insecure", "chat.example.com", "6000"});

        assertTrue(options.insecure);
        assertEquals("chat.example.com", options.host);
        assertEquals(6000, options.port);
    }

    @Test
    void aFlagWithoutItsValueIsRejected() {
        assertNull(ChatClient.parse(new String[] {"--truststore"}));
        assertNull(ChatClient.parse(new String[] {"localhost", "5000", "--truststore-password"}));
    }

    @Test
    void aPortThatIsNotANumberIsRejected() {
        assertNull(ChatClient.parse(new String[] {"localhost", "soon"}));
    }

    @Test
    void tooManyPositionalArgumentsAreRejected() {
        assertNull(ChatClient.parse(new String[] {"localhost", "5000", "extra"}));
    }
}
