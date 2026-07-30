package com.cli.chat.common;

public record Message(
    MessageType type,
    String sender,
    String recipient, //nullable
    String body,
    long timestamp
) {
    public static Message broadcast(String sender, String body) {
        return new Message(MessageType.BROADCAST, sender, null, body, System.currentTimeMillis());
    }
    public static Message system(String body) {
        return new Message(MessageType.SYSTEM, "SERVER", null, body, System.currentTimeMillis());
    }
    public static Message error(String body) {
        return new Message(MessageType.ERROR, "SERVER", null, body, System.currentTimeMillis());
    }
}
