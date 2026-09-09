package com.cli.chat.server;

import java.util.List;

import com.cli.chat.common.Message;

public class HistoryCommand implements Command {

    private static final int DEFAULT_COUNT = 20;

    @Override
    public String name() {
        return "history";
    }

    @Override
    public String usage() {
        return "/history [count]";
    }

    @Override
    public void run(ClientHandler sender, String arguments, ChatServer server) {
        int count = DEFAULT_COUNT;
        if (!arguments.isBlank()) {
            try {
                count = Integer.parseInt(arguments.strip());
            } catch (NumberFormatException e) {
                sender.send(Message.error("usage: " + usage()));
                return;
            }
        }
        if (count < 1) {
            sender.send(Message.error("usage: " + usage()));
            return;
        }
        List<Message> recent = server.recent().last(count);
        if (recent.isEmpty()) {
            sender.send(Message.system("no messages yet"));
            return;
        }
        sender.send(Message.system("last " + recent.size() + " messages"));
        for (Message message : recent) {
            sender.send(message);
        }
    }
}
