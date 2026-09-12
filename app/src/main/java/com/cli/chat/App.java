package com.cli.chat;

import java.util.Arrays;

import com.cli.chat.client.ChatClient;
import com.cli.chat.server.ChatServer;

public class App {

    static final String USAGE = "usage: java -jar cli-chat.jar <server|client> [options]";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println(USAGE);
            return;
        }
        switch (args[0]) {
            case "server" -> ChatServer.main(rest(args));
            case "client" -> ChatClient.main(rest(args));
            default -> System.out.println(USAGE);
        }
    }

    static String[] rest(String[] args) {
        return Arrays.copyOfRange(args, 1, args.length);
    }
}
