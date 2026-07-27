package com.cli.chat.common;

public record Message(
    MessageType type,
    String sender,
    String recipient, //nullable
    String body,
    long timestamp
) {

}
