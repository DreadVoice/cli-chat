package com.cli.chat.server;

import com.cli.chat.common.Message;
import com.cli.chat.common.MessageType;

public class WhisperCommand implements Command {

    @Override
    public String name() {
        return "whisper";
    }

    @Override
    public String usage() {
        return "/whisper <user> <message>";
    }

    @Override
    public void run(ClientHandler sender, String arguments, ChatServer server) {
        String[] parts = arguments.strip().split("\\s+", 2);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            sender.send(Message.error("usage: " + usage()));
            return;
        }
        sender.deliverPrivate(new Message(MessageType.PRIVATE, sender.getUsername(), parts[0], parts[1],
                System.currentTimeMillis()), server.registry());
    }
}
