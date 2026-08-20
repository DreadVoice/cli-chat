package com.cli.chat.server;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.cli.chat.common.Message;
import com.cli.chat.common.Protocol;

public class ClientRegistry {

    private final Map<String, ClientHandler> byName = new ConcurrentHashMap<>();

    /**
     * Claims {@code username} for {@code handler} if no one holds it yet.
     * The check and the insert are a single atomic operation, so two clients
     * racing on the same name cannot both succeed.
     *
     * @return true if the name was free and is now taken by this handler
     */
    public boolean addIfAbsent(String username, ClientHandler handler) {
        return byName.putIfAbsent(username, handler) == null;
    }

    /** Releases {@code username}, but only if {@code handler} is the current holder. */
    public boolean remove(String username, ClientHandler handler) {
        return byName.remove(username, handler);
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

    /**
     * Sends {@code msg} to every client except {@code sender}. The message is
     * serialised once and the same line is written to each recipient, so the
     * cost of encoding does not scale with the number of clients online.
     */
    public void broadcast(Message msg, ClientHandler sender) {
        String line;
        try {
            line = Protocol.encode(msg);
        } catch (IOException e) {
            return;             // unserialisable message; nobody can receive it
        }
        for (ClientHandler c : byName.values()) {
            if (c != sender) {
                c.sendRaw(line);
            }
        }
    }
}