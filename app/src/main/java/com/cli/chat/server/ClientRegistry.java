package com.cli.chat.server;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cli.chat.common.Message;
import com.cli.chat.common.Protocol;
import com.cli.chat.common.exception.ProtocolException;

public class ClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(ClientRegistry.class);

    private final Map<String, ClientHandler> byName = new ConcurrentHashMap<>();

    public boolean addIfAbsent(String username, ClientHandler handler) {
        return byName.putIfAbsent(username, handler) == null;
    }

    public boolean remove(String username, ClientHandler handler) {
        return byName.remove(username, handler);
    }

    public Optional<ClientHandler> find(String username) {
        return Optional.ofNullable(byName.get(username));
    }

    public List<String> onlineUsers() {
        return byName.keySet().stream().sorted().toList();
    }

    public int size() {
        return byName.size();
    }

    public void disconnectAll() {
        for (ClientHandler c : byName.values()) {
            c.disconnect();
        }
    }

    public void broadcast(Message msg, ClientHandler sender) {
        String line;
        try {
            line = Protocol.encode(msg);
        } catch (ProtocolException e) {
            log.error("dropping unserialisable broadcast from {}", msg.sender(), e);
            return;
        }
        for (ClientHandler c : byName.values()) {
            if (c != sender) {
                c.sendRaw(line);
            }
        }
    }
}