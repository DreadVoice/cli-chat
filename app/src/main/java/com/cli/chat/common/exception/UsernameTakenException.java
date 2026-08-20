package com.cli.chat.common.exception;

public class UsernameTakenException extends AuthenticationException {

    private final String username;

    public UsernameTakenException(String username) {
        super("username '" + username + "' is already taken");
        this.username = username;
    }

    public String username() {
        return username;
    }
}
