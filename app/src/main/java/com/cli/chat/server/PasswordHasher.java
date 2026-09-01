package com.cli.chat.server;

import at.favre.lib.crypto.bcrypt.BCrypt;

public class PasswordHasher {

    private static final int COST = 12;

    private PasswordHasher() {
    }

    public static String hash(String password) {
        return BCrypt.withDefaults().hashToString(COST, password.toCharArray());
    }

    public static boolean matches(String password, String hash) {
        return BCrypt.verifyer().verify(password.toCharArray(), hash).verified;
    }
}
