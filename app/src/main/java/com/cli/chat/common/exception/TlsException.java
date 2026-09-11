package com.cli.chat.common.exception;

public class TlsException extends ChatException {

    public TlsException(String message) {
        super(message);
    }

    public TlsException(String message, Throwable cause) {
        super(message, cause);
    }
}
