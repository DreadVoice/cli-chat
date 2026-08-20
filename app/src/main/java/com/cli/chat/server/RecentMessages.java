package com.cli.chat.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

import com.cli.chat.common.Message;

public class RecentMessages {

    private final int capacity;
    private final Deque<Message> buffer;

    public RecentMessages(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(capacity);
    }

    public synchronized void add(Message message) {
        if (buffer.size() == capacity) {
            buffer.removeFirst();
        }
        buffer.addLast(message);
    }

    public synchronized void addAll(Collection<Message> messages) {
        for (Message message : messages) {
            add(message);
        }
    }

    public synchronized List<Message> last(int count) {
        int wanted = Math.min(count, buffer.size());
        if (wanted <= 0) {
            return List.of();
        }
        List<Message> all = new ArrayList<>(buffer);
        return List.copyOf(all.subList(all.size() - wanted, all.size()));
    }

    public synchronized int size() {
        return buffer.size();
    }

    public int capacity() {
        return capacity;
    }
}
