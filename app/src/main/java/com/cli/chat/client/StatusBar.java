package com.cli.chat.client;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;

class StatusBar {

    private static final AttributedStyle STYLE = AttributedStyle.DEFAULT
            .background(AttributedStyle.BLUE)
            .foreground(AttributedStyle.WHITE);

    private final Status status;
    private final AtomicInteger pendingRefreshes = new AtomicInteger();

    private volatile String username = "not signed in";
    private volatile boolean connected;
    private volatile int online;

    StatusBar(Terminal terminal) {
        this.status = Status.getStatus(terminal);
    }

    void connected(String username) {
        this.username = username;
        this.connected = true;
        redraw();
    }

    void disconnected() {
        this.connected = false;
        redraw();
    }

    void online(int count) {
        this.online = count;
        redraw();
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

    private void redraw() {
        if (status == null) {
            return;
        }
        status.update(List.of(new AttributedString(text(), STYLE)));
    }
}
