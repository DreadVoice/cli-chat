package com.cli.chat.common;

import com.cli.chat.common.exception.ProtocolException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Protocol {
    private final static ObjectMapper MAPPER = new ObjectMapper();

    private Protocol() {
        //static
    }

    // Serialise message into single JSON line
    public static String encode(Message message) throws ProtocolException {
        try {
            return MAPPER.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new ProtocolException("could not encode message", e);
        }
    }
    //Parse one line of JSON back into a message
    public static Message decode(String json) throws ProtocolException {
        try {
            return MAPPER.readValue(json, Message.class);
        } catch (JsonProcessingException e) {
            throw new ProtocolException("could not parse as JSON", e);
        }
    }
}
