package com.cli.chat.server;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public class Usernames {

    static final int MAX_LENGTH = 24;

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._-]+");
    private static final String RESERVED = "server";

    private Usernames() {
    }

    static Optional<String> rejection(String username) {
        if (username == null || username.isBlank()) {
            return Optional.of("a username is required");
        }
        if (username.length() > MAX_LENGTH) {
            return Optional.of("a username can be at most " + MAX_LENGTH + " characters");
        }
        if (!ALLOWED.matcher(username).matches()) {
            return Optional.of("a username can only use letters, digits, '.', '_' and '-'");
        }
        if (username.toLowerCase(Locale.ROOT).equals(RESERVED)) {
            return Optional.of("username '" + username + "' is reserved");
        }
        return Optional.empty();
    }
}
