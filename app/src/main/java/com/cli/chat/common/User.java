package com.cli.chat.common;

public record User(
    long id,
    String username,
    String passwordHash,
    long createdAt
) {}
