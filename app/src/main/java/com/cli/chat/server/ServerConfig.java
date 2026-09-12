package com.cli.chat.server;

import java.util.Set;

import com.cli.chat.db.MessageRepository;
import com.cli.chat.db.MessageWriter;
import com.cli.chat.db.UserRepository;
import com.cli.chat.net.PlainSocketFactory;
import com.cli.chat.net.SocketFactory;

public record ServerConfig(
    int port,
    MessageWriter writer,
    MessageRepository history,
    UserRepository users,
    Set<String> admins,
    SocketFactory sockets
) {

    public ServerConfig {
        admins = admins == null ? Set.of() : Set.copyOf(admins);
        sockets = sockets == null ? new PlainSocketFactory() : sockets;
    }

    public static ServerConfig onPort(int port) {
        return new ServerConfig(port, null, null, null, Set.of(), new PlainSocketFactory());
    }

    public ServerConfig withStorage(MessageWriter writer, MessageRepository history) {
        return new ServerConfig(port, writer, history, users, admins, sockets);
    }

    public ServerConfig withUsers(UserRepository users) {
        return new ServerConfig(port, writer, history, users, admins, sockets);
    }

    public ServerConfig withAdmins(Set<String> admins) {
        return new ServerConfig(port, writer, history, users, admins, sockets);
    }

    public ServerConfig withSockets(SocketFactory sockets) {
        return new ServerConfig(port, writer, history, users, admins, sockets);
    }

    public boolean isAdmin(String username) {
        return admins.contains(username);
    }
}
