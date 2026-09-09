package com.cli.chat.server;

import com.cli.chat.common.Message;

public class HelpCommand implements Command {

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String usage() {
        return "/help";
    }

    @Override
    public void run(ClientHandler sender, String arguments, ChatServer server) {
        sender.send(Message.system("commands:"));
        for (Command command : server.commands().all()) {
            sender.send(Message.system(command.usage()));
        }
    }
}
