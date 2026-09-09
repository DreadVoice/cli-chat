package com.cli.chat.server;

public interface Command {

    String name();

    String usage();

    void run(ClientHandler sender, String arguments, ChatServer server);
}
