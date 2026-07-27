package com.cli.chat.common;

public enum MessageType {
    // client to server
    LOGIN,
    REGISTER,
    CHAT,
    PRIVATE,
    COMMAND,
    QUIT,

    // server to client
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    BROADCAST,
    PRIVATE_DELIVERY,
    SYSTEM,
    ERROR,
    USER_LIST
}
