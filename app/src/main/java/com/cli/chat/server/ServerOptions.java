package com.cli.chat.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public record ServerOptions(
    int port,
    String database,
    Set<String> admins,
    String keystore,
    String keystorePassword
) {

    public static final String USAGE = "usage: ChatServer [port] [database] [admins] [--port <port>] "
            + "[--database <path>] [--admins <a,b>] [--keystore <path>] [--keystore-password <password>]";

    static final int DEFAULT_PORT = 5000;
    static final String DEFAULT_DATABASE = "chat.db";

    public static ServerOptions parse(String[] args) {
        int port = DEFAULT_PORT;
        String database = DEFAULT_DATABASE;
        Set<String> admins = Set.of();
        String keystore = null;
        String keystorePassword = null;
        List<String> positional = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> {
                    if (++i == args.length) return null;
                    Integer parsed = number(args[i]);
                    if (parsed == null) return null;
                    port = parsed;
                }
                case "--database" -> {
                    if (++i == args.length) return null;
                    database = args[i];
                }
                case "--admins" -> {
                    if (++i == args.length) return null;
                    admins = names(args[i]);
                }
                case "--keystore" -> {
                    if (++i == args.length) return null;
                    keystore = args[i];
                }
                case "--keystore-password" -> {
                    if (++i == args.length) return null;
                    keystorePassword = args[i];
                }
                case "--help" -> {
                    return null;
                }
                default -> positional.add(args[i]);
            }
        }
        if (positional.size() > 3) {
            return null;
        }
        if (!positional.isEmpty() && !hasFlag(args, "--port")) {
            Integer parsed = number(positional.get(0));
            if (parsed == null) return null;
            port = parsed;
        }
        if (positional.size() > 1 && !hasFlag(args, "--database")) {
            database = positional.get(1);
        }
        if (positional.size() > 2 && !hasFlag(args, "--admins")) {
            admins = names(positional.get(2));
        }
        return new ServerOptions(port, database, admins, keystore, keystorePassword);
    }

    private static boolean hasFlag(String[] args, String flag) {
        return Arrays.asList(args).contains(flag);
    }

    private static Integer number(String argument) {
        try {
            return Integer.valueOf(argument);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Set<String> names(String argument) {
        return Arrays.stream(argument.split(","))
                .map(String::strip)
                .filter(name -> !name.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
}
