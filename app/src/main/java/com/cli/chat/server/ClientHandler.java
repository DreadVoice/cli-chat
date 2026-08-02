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
        ClientRegistry registry = server.registry();
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream()))) {
            out = new PrintWriter(socket.getOutputStream(), true);

            send(Message.system("Enter your name:"));
            String first = in.readLine();
            name = (first == null || first.isBlank()) ? "anon" : first;

            registry.add(name, this);       // register once the name is known
            registry.broadcast(Message.system(name + " joined"), this);

            String line;
            while ((line = in.readLine()) != null) {
                Message msg;
                try {
                    msg = Protocol.decode(line);
                } catch (IOException e) {
                    send(Message.error("malformed message: could not parse as JSON"));
                    continue;
                }
                if (msg.type() == null) {
                    send(Message.error("malformed message: missing or unknown type"));
                    continue;
                }
                switch (msg.type()) {
                    case CHAT -> registry.broadcast(Message.broadcast(name, msg.body()), this);
                    case QUIT -> { return; }
                    default   -> send(Message.error("unexpected type: " + msg.type()));
                }
            }
        } catch (IOException e) {
            // client dropped
        } finally {
            close(registry);
        }
    }

    private void close(ClientRegistry registry) {
        registry.remove(name);
        registry.broadcast(Message.system(name + " left"), this);
        try { socket.close(); } catch (IOException ignored) {}
    }

    String getUsername() {
        return name;
    }

    void send(Message msg) {
        if (out == null) return;
        try {
            out.println(Protocol.encode(msg));
        } catch (IOException e) {
            // message could not be serialised; drop it
        }
    }
}