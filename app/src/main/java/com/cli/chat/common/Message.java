package com.cli.chat.common;

import java.util.Collection;
import java.util.List;

public record Message(
    MessageType type,
    String sender,
    String recipient, 
    String body,
    long timestamp
) {
    public static Message broadcast(String sender, String body) {
        return new Message(MessageType.BROADCAST, sender, null, body, System.currentTimeMillis());
    }
    public static Message system(String body) {
        return new Message(MessageType.SYSTEM, "SERVER", null, body, System.currentTimeMillis());
    }
    public static Message userList(Collection<String> usernames) {
        return new Message(MessageType.USER_LIST, "SERVER", null,
                String.join(", ", List.copyOf(usernames)), System.currentTimeMillis());
    }
    public static Message privateDelivery(String sender, String recipient, String body) {
        return new Message(MessageType.PRIVATE_DELIVERY, sender, recipient, body, System.currentTimeMillis());
    }
    public static Message loginOk(String username) {
        return new Message(MessageType.LOGIN_OK, "SERVER", username,
                "welcome, " + username, System.currentTimeMillis());
    }
    public static Message loginFail(String reason) {
        return new Message(MessageType.LOGIN_FAIL, "SERVER", null, reason, System.currentTimeMillis());
    }
    public static Message error(String body) {
        return new Message(MessageType.ERROR, "SERVER", null, body, System.currentTimeMillis());
    }
}
