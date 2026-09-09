package com.cli.chat.server;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class CommandRegistry {

    private final Map<String, Command> byName = new ConcurrentHashMap<>();

    public void register(Command command) {
        String key = key(command.name());
        if (byName.putIfAbsent(key, command) != null) {
            throw new IllegalArgumentException("command '" + key + "' is already registered");
        }
    }

    public Optional<Command> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName.get(key(name)));
    }

    public List<Command> all() {
        return byName.values().stream()
                .sorted(Comparator.comparing(Command::name))
                .toList();
    }

    public int size() {
        return byName.size();
    }

    private static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }
}
