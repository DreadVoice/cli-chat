package com.cli.chat.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

import com.cli.chat.common.Message;
import com.cli.chat.common.MessageType;
import com.cli.chat.common.Protocol;

public class ChatClient {

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 5000;

    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;

        try (Socket socket = new Socket(host, port);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader console = new BufferedReader(
                     new InputStreamReader(System.in))) {

            String username = handshake(in, out, console);

            Thread reader = new Thread(() -> receiveLoop(in));
            reader.setDaemon(true);
            reader.start();

            sendLoop(console, out, username);
        }
    }

    private static String handshake(BufferedReader in, PrintWriter out,
                                    BufferedReader console) throws IOException {
        Message prompt = readMessage(in);           // SYSTEM "Enter your name:"
        if (prompt != null) render(prompt);

        String name = console.readLine();
        if (name == null || name.isBlank()) name = "anon";
        out.println(name);                           //raw
        return name;
    }

    private static void receiveLoop(BufferedReader in) {
        try {
            Message msg;
            while ((msg = readMessage(in)) != null) {
                render(msg);
            }
        } catch (IOException e) {
            System.out.println("Disconnected.");
        }
    }

    private static void sendLoop(BufferedReader console, PrintWriter out,
                                 String username) throws IOException {
        String line;
        while ((line = console.readLine()) != null) {
            if (line.equalsIgnoreCase("/quit")) {
                out.println(encode(new Message(
                        MessageType.QUIT, username, null, null, System.currentTimeMillis())));
                break;
            }
            if (line.equalsIgnoreCase("/who")) {
                out.println(encode(new Message(
                        MessageType.USER_LIST, username, null, null, System.currentTimeMillis())));
                continue;
            }
            out.println(encode(new Message(
                    MessageType.CHAT, username, null, line, System.currentTimeMillis())));
        }
    }


    private static Message readMessage(BufferedReader in) throws IOException {
        String line = in.readLine();
        if (line == null) return null;
        try {
            return Protocol.decode(line);
        } catch (IOException e) {
            return Message.system(line);
        }
    }

    private static String encode(Message msg) {
        try {
            return Protocol.encode(msg);
        } catch (IOException e) {
            throw new RuntimeException("failed to encode outgoing message", e);
        }
    }

    private static void render(Message msg) {
        switch (msg.type()) {
            case BROADCAST, PRIVATE_DELIVERY ->
                    System.out.println("[" + msg.sender() + "] " + msg.body());
            case SYSTEM  -> System.out.println("*** " + msg.body() + " ***");
            case USER_LIST -> System.out.println("--- online: " + msg.body() + " ---");
            case ERROR   -> System.out.println("!!! " + msg.body());
            default      -> System.out.println(msg.body());
        }
    }
}