package com.cli.chat.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

import com.cli.chat.common.Message;
import com.cli.chat.common.Protocol;

public class ClientHandler implements Runnable {

    private final Socket socket;
    private final ChatServer server;
    private PrintWriter out;
    private String name = "anon";

    ClientHandler(Socket socket, ChatServer server) {
        this.socket = socket;
        this.server = server;
    }

    @Override
    public void run() {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream()))) {
            out = new PrintWriter(socket.getOutputStream(), true);

            out.println("Enter your name:");
            name = in.readLine();
            if (name == null || name.isBlank()) name = "anon";
            server.broadcast(Message.system("*** " + name + " joined ***"), this);

            String line;
            while ((line = in.readLine()) != null) {
                Message msg;
                try {
                    msg = Protocol.decode(line);
                } catch (IOException e) {
                    // malformed line; skip it
                    continue;
                }
                switch (msg.type()) {
                    case BROADCAST -> server.broadcast(Message.broadcast(name, msg.body()), this);
                    case QUIT -> {
                        return;
                    }
                    default -> send(Message.error("Unexpected message type: " + msg.type()));
                }
            }
        } catch (IOException e) {
            // client dropped
        } finally {
            close();
        }
    }

    void send(Message msg) {
        if (out == null) return;
        try {
            out.println(Protocol.encode(msg));
        } catch (IOException e) {
            // message could not be serialised; drop it
        }
    }

    private void close() {
        server.remove(this);
        server.broadcast(Message.system("*** " + name + " left ***"), this);
        try { socket.close(); } catch (IOException ignored) {}
    }
}