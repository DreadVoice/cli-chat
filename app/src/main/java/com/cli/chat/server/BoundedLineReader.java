package com.cli.chat.server;

import java.io.BufferedReader;
import java.io.IOException;

public class BoundedLineReader {

    private final BufferedReader in;
    private final int limit;

    BoundedLineReader(BufferedReader in, int limit) {
        this.in = in;
        this.limit = limit;
    }

    Line next() throws IOException {
        StringBuilder text = new StringBuilder();
        boolean tooLong = false;
        int read;
        while ((read = in.read()) != -1) {
            char c = (char) read;
            if (c == '\n') {
                return new Line(text.toString(), tooLong);
            }
            if (c == '\r' || tooLong) {
                continue;
            }
            if (text.length() == limit) {
                tooLong = true;
                text.setLength(0);
                continue;
            }
            text.append(c);
        }
        if (tooLong || !text.isEmpty()) {
            return new Line(text.toString(), tooLong);
        }
        return null;
    }

    record Line(String text, boolean tooLong) {}
}
