package com.cli.chat.client;

import java.util.concurrent.atomic.AtomicInteger;

import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

class StatusBar {

    private static final AttributedStyle STATE = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);
    private static final AttributedStyle PROMPT = AttributedStyle.DEFAULT.bold();

    private final AtomicInteger pendingRefreshes = new AtomicInteger();
    private final AtomicInteger rostersAskedFor = new AtomicInteger();

    private volatile String username = "not signed in";
    private volatile boolean connected;
    private volatile int online;

    void connected(String username) {
        this.username = username;
        this.connected = true;
    }

    void disconnected() {
        this.connected = false;
    }

    void online(int count) {
        this.online = count;
    }

    void rosterAskedFor() {
        rostersAskedFor.incrementAndGet();
    }

    boolean consumeAsk() {
        return rostersAskedFor.getAndUpdate(asked -> asked > 0 ? asked - 1 : 0) > 0;
    }

    void refreshRequested() {
        pendingRefreshes.incrementAndGet();
    }

    boolean consumeRefresh() {
        return pendingRefreshes.getAndUpdate(pending -> pending > 0 ? pending - 1 : 0) > 0;
    }

    static int count(String roster) {
        if (roster == null || roster.isBlank()) {
            return 0;
        }
        return roster.split(",").length;
    }

    String text() {
        return (connected ? "connected" : "disconnected") + "  " + username + "  " + online + " online";
    }

    String prompt() {
        return new AttributedStringBuilder()
                .styled(STATE, "[" + username + " " + online + " online]")
                .styled(PROMPT, "> ")
                .toAnsi();
    }
}
