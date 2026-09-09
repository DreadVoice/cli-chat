package com.cli.chat.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cli.chat.common.Message;

public class ShutdownCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(ShutdownCommand.class);

    @Override
    public String name() {
        return "shutdown";
    }

    @Override
    public String usage() {
        return "/shutdown";
    }

    @Override
    public void run(ClientHandler sender, String arguments, ChatServer server) {
        if (!sender.isAdmin()) {
            log.warn("{} tried to shut the server down without being an admin", sender.getUsername());
            sender.send(Message.error("/shutdown is for admins"));
            return;
        }
        log.info("{} asked for a shutdown", sender.getUsername());
        Message notice = Message.system("server shutting down");
        server.registry().broadcast(notice, sender);
        sender.send(notice);
        new Thread(server::stop, "shutdown-command").start();
    }
}
