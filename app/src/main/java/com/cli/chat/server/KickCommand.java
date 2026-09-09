package com.cli.chat.server;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cli.chat.common.Message;

public class KickCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(KickCommand.class);

    @Override
    public String name() {
        return "kick";
    }

    @Override
    public String usage() {
        return "/kick <user>";
    }

    @Override
    public void run(ClientHandler sender, String arguments, ChatServer server) {
        if (!sender.isAdmin()) {
            log.warn("{} tried to kick without being an admin", sender.getUsername());
            sender.send(Message.error("/kick is for admins"));
            return;
        }
        String target = arguments.strip();
        if (target.isBlank()) {
            sender.send(Message.error("usage: " + usage()));
            return;
        }
        Optional<ClientHandler> victim = server.registry().find(target);
        if (victim.isEmpty()) {
            sender.send(Message.error("user '" + target + "' is not online"));
            return;
        }
        log.info("{} kicked {}", sender.getUsername(), target);
        victim.get().send(Message.system("you were kicked by " + sender.getUsername()));
        victim.get().disconnect();
        sender.send(Message.system("kicked " + target));
    }
}
