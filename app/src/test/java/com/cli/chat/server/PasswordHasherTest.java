package com.cli.chat.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import at.favre.lib.crypto.bcrypt.BCrypt;

class PasswordHasherTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Test
    void hashesWithBcryptAtCostTwelve() {
        assertTrue(PasswordHasher.hash(PASSWORD).startsWith("$2a$12$"),
                "the hash should carry the bcrypt prefix and cost 12");
    }

    @Test
    void theHashNeverContainsThePassword() {
        assertFalse(PasswordHasher.hash(PASSWORD).contains(PASSWORD));
    }

    @Test
    void theSamePasswordHashesDifferentlyEveryTime() {
        assertNotEquals(PasswordHasher.hash(PASSWORD), PasswordHasher.hash(PASSWORD),
                "each hash should carry its own salt");
    }

    @Test
    void theHashVerifiesAgainstTheOriginalPasswordOnly() {
        String hash = PasswordHasher.hash(PASSWORD);

        assertTrue(BCrypt.verifyer().verify(PASSWORD.toCharArray(), hash).verified);
        assertFalse(BCrypt.verifyer().verify("wrong horse".toCharArray(), hash).verified);
    }
}
