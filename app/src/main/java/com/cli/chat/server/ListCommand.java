package com.cli.chat.server;

import com.cli.chat.common.Message;

public class ListCommand implements Command {

    @Override
    public String name() {
        return "list";
    }

    @Override
    public String usage() {
        return "/list";
    }

    @Override
    public void run(ClientHandler sender, String arguments, ChatServer server) {
        sender.send(Message.userList(server.registry().onlineUsers()));
    }
}
