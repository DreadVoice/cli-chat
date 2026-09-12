package com.cli.chat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class AppTest {

    @Test
    void theCommandIsDroppedBeforeTheRestIsPassedOn() {
        assertArrayEquals(new String[] {"--port", "6000"},
                App.rest(new String[] {"server", "--port", "6000"}));
        assertArrayEquals(new String[0], App.rest(new String[] {"client"}));
    }

    @Test
    void withoutACommandTheUsageIsPrinted() throws Exception {
        assertEquals(App.USAGE, run());
    }

    @Test
    void anUnknownCommandPrintsTheUsageToo() throws Exception {
        assertEquals(App.USAGE, run("chat"));
    }

    @Test
    void theUsageNamesBothHalves() {
        assertTrue(App.USAGE.contains("server"));
        assertTrue(App.USAGE.contains("client"));
    }

    private static String run(String... args) throws Exception {
        PrintStream out = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            App.main(args);
        } finally {
            System.setOut(out);
        }
        return captured.toString(StandardCharsets.UTF_8).strip();
    }
}
