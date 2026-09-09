package com.cli.chat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommandRegistryTest {

    private CommandRegistry commands;

    @BeforeEach
    void newRegistry() {
        commands = new CommandRegistry();
    }

    @Test
    void aRegisteredCommandIsFoundByName() {
        Command who = new StubCommand("who", "/who");
        commands.register(who);

        assertEquals(who, commands.find("who").orElseThrow());
    }

    @Test
    void lookupIgnoresCaseAndSurroundingSpace() {
        commands.register(new StubCommand("who", "/who"));

        assertTrue(commands.find("WHO").isPresent());
        assertTrue(commands.find("  Who  ").isPresent());
    }

    @Test
    void anUnknownNameIsNotFound() {
        commands.register(new StubCommand("who", "/who"));

        assertTrue(commands.find("nope").isEmpty());
    }

    @Test
    void aMissingNameIsNotFound() {
        assertTrue(commands.find(null).isEmpty());
        assertTrue(commands.find("   ").isEmpty());
    }

    @Test
    void everyCommandIsListedInNameOrder() {
        commands.register(new StubCommand("who", "/who"));
        commands.register(new StubCommand("help", "/help"));
        commands.register(new StubCommand("msg", "/msg <user> <text>"));

        assertEquals(List.of("help", "msg", "who"), commands.all().stream().map(Command::name).toList());
        assertEquals(3, commands.size());
    }

    @Test
    void aNameCanOnlyBeRegisteredOnce() {
        commands.register(new StubCommand("who", "/who"));

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> commands.register(new StubCommand("WHO", "/who")));
        assertTrue(rejected.getMessage().contains("who"), "the clash should name the command");
    }

    private record StubCommand(String name, String usage) implements Command {

        @Override
        public void run(ClientHandler sender, String arguments, ChatServer server) {
        }
    }
}
