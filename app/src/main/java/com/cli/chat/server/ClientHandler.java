package com.cli.chat.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cli.chat.common.Message;
import com.cli.chat.common.Protocol;
import com.cli.chat.common.exception.ProtocolException;

public class ClientHandler implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);

    private final Socket socket;
    private final ChatServer server;
    private PrintWriter out;
    private String name = "anon";
    private boolean registered;

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

            if (!register(in, registry)) return;
            log.info("{} joined, {} online", name, registry.size());
            registry.broadcast(Message.system(name + " joined"), this);

            String line;
            while ((line = in.readLine()) != null) {
                Message msg;
                try {
                    msg = Protocol.decode(line);
                } catch (ProtocolException e) {
                    send(Message.error("malformed message: " + e.getMessage()));
                    continue;
                }
                if (msg.type() == null) {
                    send(Message.error("malformed message: missing or unknown type"));
                    continue;
                }
                switch (msg.type()) {
                    case CHAT -> registry.broadcast(Message.broadcast(name, msg.body()), this);
                    case USER_LIST -> send(Message.userList(registry.onlineUsers()));
                    case QUIT -> { return; }
                    default   -> send(Message.error("unexpected type: " + msg.type()));
                }
            }
        } catch (IOException e) {
            log.debug("connection to {} dropped", name, e);
        } finally {
            close(registry);
        }
    }

    private boolean register(BufferedReader in, ClientRegistry registry) throws IOException {
        send(Message.system("Enter your name:"));
        String line;
        while ((line = in.readLine()) != null) {
            String candidate = line.isBlank() ? "anon" : line;
            if (registry.addIfAbsent(candidate, this)) {
                name = candidate;
                registered = true;
                return true;
            }
            send(Message.error("username '" + candidate + "' is already taken"));
            send(Message.system("Enter your name:"));
        }
        return false;
    }

    private void close(ClientRegistry registry) {
        if (registered) {
            registry.remove(name, this);
            log.info("{} left, {} online", name, registry.size());
            registry.broadcast(Message.system(name + " left"), this);
        }
        try {
            socket.close();
        } catch (IOException e) {
            log.debug("failed to close socket for {}", name, e);
        }
    }

    String getUsername() {
        return name;
    }

    void send(Message msg) {
        if (out == null) return;
        try {
            sendRaw(Protocol.encode(msg));
        } catch (ProtocolException e) {
            
        }
    }

    void sendRaw(String line) {
        if (out == null) return;
        out.println(line);
    }
}