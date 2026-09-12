package com.cli.chat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class ServerOptionsTest {

    @Test
    void nothingGivenMeansTheDefaults() {
        ServerOptions options = ServerOptions.parse(new String[0]);

        assertEquals(5000, options.port());
        assertEquals("chat.db", options.database());
        assertTrue(options.admins().isEmpty());
        assertNull(options.keystore());
        assertNull(options.keystorePassword());
    }

    @Test
    void theOldPositionalFormStillWorks() {
        ServerOptions options = ServerOptions.parse(new String[] {"6000", "data/chat.db", "root,ops"});

        assertEquals(6000, options.port());
        assertEquals("data/chat.db", options.database());
        assertEquals(Set.of("root", "ops"), options.admins());
    }

    @Test
    void everySettingHasAFlag() {
        ServerOptions options = ServerOptions.parse(new String[] {
                "--port", "6000",
                "--database", "data/chat.db",
                "--admins", "root",
                "--keystore", "keystore.p12",
                "--keystore-password", "changeit"});

        assertEquals(6000, options.port());
        assertEquals("data/chat.db", options.database());
        assertEquals(Set.of("root"), options.admins());
        assertEquals("keystore.p12", options.keystore());
        assertEquals("changeit", options.keystorePassword());
    }

    @Test
    void aFlagWinsOverThePositionalItReplaces() {
        ServerOptions options = ServerOptions.parse(new String[] {"6000", "old.db", "--database", "new.db"});

        assertEquals(6000, options.port(), "the positional port still counts");
        assertEquals("new.db", options.database(), "the flag should win");
    }

    @Test
    void adminNamesAreTrimmedAndBlanksDropped() {
        ServerOptions options = ServerOptions.parse(new String[] {"--admins", " root , , ops "});

        assertEquals(Set.of("root", "ops"), options.admins());
    }

    @Test
    void aPortThatIsNotANumberIsRejected() {
        assertNull(ServerOptions.parse(new String[] {"soon"}));
        assertNull(ServerOptions.parse(new String[] {"--port", "soon"}));
    }

    @Test
    void aFlagWithoutItsValueIsRejected() {
        assertNull(ServerOptions.parse(new String[] {"--keystore"}));
        assertNull(ServerOptions.parse(new String[] {"5000", "chat.db", "--admins"}));
    }

    @Test
    void tooManyPositionalArgumentsAreRejected() {
        assertNull(ServerOptions.parse(new String[] {"5000", "chat.db", "root", "extra"}));
    }

    @Test
    void helpAsksForTheUsage() {
        assertNull(ServerOptions.parse(new String[] {"--help"}));
        assertTrue(ServerOptions.USAGE.contains("--keystore"), "the usage should name every flag");
        assertTrue(ServerOptions.USAGE.contains("--admins"));
    }

    @Test
    void theKeystorePasswordPrefersTheFlag() {
        ServerOptions given = ServerOptions.parse(new String[] {"--keystore-password", "from-the-flag"});

        assertEquals("from-the-flag", ChatServer.keystorePassword(given));
    }

    @Test
    void withoutAFlagTheKeystorePasswordFallsBackToThePropertyOrIsEmpty() {
        ServerOptions none = ServerOptions.parse(new String[0]);
        String property = System.getProperty("chat.keystore.password");
        try {
            System.setProperty("chat.keystore.password", "from-the-property");
            assertEquals("from-the-property", ChatServer.keystorePassword(none));

            System.clearProperty("chat.keystore.password");
            assertEquals(System.getenv("CHAT_KEYSTORE_PASSWORD") != null
                    ? System.getenv("CHAT_KEYSTORE_PASSWORD") : "", ChatServer.keystorePassword(none));
        } finally {
            if (property != null) {
                System.setProperty("chat.keystore.password", property);
            } else {
                System.clearProperty("chat.keystore.password");
            }
        }
    }
}
