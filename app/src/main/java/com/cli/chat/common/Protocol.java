package com.cli.chat.common;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;

public class Protocol {
    private final static ObjectMapper MAPPER = new ObjectMapper();

    private Protocol() {
    }

    public static String encode(Message message) throws IOException {
        return MAPPER.writeValueAsString(message);
    }
    public static Message decode(String json) throws IOException {
        return MAPPER.readValue(json, Message.class);
    }
}
