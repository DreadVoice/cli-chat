package com.cli.chat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.cli.chat.net.PlainSocketFactory;

class ServerConfigTest {

    @Test
    void aPortIsEnoughToStartFrom() {
        ServerConfig config = ServerConfig.onPort(5000);

        assertEquals(5000, config.port());
        assertNull(config.writer());
        assertNull(config.history());
        assertNull(config.users());
        assertTrue(config.admins().isEmpty());
        assertInstanceOf(PlainSocketFactory.class, config.sockets(), "plain sockets unless told otherwise");
    }

    @Test
    void missingAdminsAndSocketsFallBack() {
        ServerConfig config = new ServerConfig(5000, null, null, null, null, null);

        assertTrue(config.admins().isEmpty());
        assertInstanceOf(PlainSocketFactory.class, config.sockets());
    }

    @Test
    void theAdminListIsACopyAndCannotBeChangedLater() {
        Set<String> admins = new HashSet<>(Set.of("root"));
        ServerConfig config = ServerConfig.onPort(5000).withAdmins(admins);

        admins.add("impostor");

        assertTrue(config.isAdmin("root"));
        assertFalse(config.isAdmin("impostor"), "the config keeps its own copy");
        assertThrows(UnsupportedOperationException.class, () -> config.admins().add("intruder"));
    }

    @Test
    void eachSettingLeavesTheOthersAlone() {
        ServerConfig config = ServerConfig.onPort(5000)
                .withAdmins(Set.of("root"))
                .withSockets(new PlainSocketFactory());

        assertEquals(5000, config.port());
        assertTrue(config.isAdmin("root"));
        assertNull(config.users(), "nothing else should have been set");
    }

    @Test
    void aConfigIsValueEqual() {
        PlainSocketFactory sockets = new PlainSocketFactory();

        assertEquals(new ServerConfig(5000, null, null, null, Set.of("root"), sockets),
                new ServerConfig(5000, null, null, null, Set.of("root"), sockets));
    }
}
