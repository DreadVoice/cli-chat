package com.cli.chat.server;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.cli.chat.common.Message;

public class ClientRegistry {

    private final Map<String, ClientHandler> byName = new ConcurrentHashMap<>();

    public void add(String username, ClientHandler handler) {
        byName.put(username, handler);
    }

    public void remove(String username) {
        byName.remove(username);
    }

    public Optional<ClientHandler> find(String username) {
        return Optional.ofNullable(byName.get(username));
    }

    public Set<String> onlineUsers() {
        return Set.copyOf(byName.keySet());
    }

    public int size() {
        return byName.size();
    }

    public void broadcast(Message msg, ClientHandler sender) {
        for (ClientHandler c : byName.values()) {
            if (c != sender) {
                c.send(msg);
            }
        }
    }
}