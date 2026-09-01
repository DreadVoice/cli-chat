package com.cli.chat.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cli.chat.common.Message;
import com.cli.chat.common.Protocol;
import com.cli.chat.common.exception.ProtocolException;
import com.cli.chat.db.MessageWriter;

public class ClientHandler implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);

    private static final int HISTORY_SIZE = 20;
    private static final String NAME_PROMPT = "Enter your name:";

    private enum State { AWAITING_AUTH, AUTHENTICATED, CLOSED }

    private final Socket socket;
    private final ChatServer server;
    private PrintWriter out;
    private String name = "anon";
    private State state = State.AWAITING_AUTH;

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
            send(Message.system(NAME_PROMPT));

            String line;
            while (state != State.CLOSED && (line = in.readLine()) != null) {
                switch (state) {
                    case AWAITING_AUTH -> authenticate(line, registry);
                    case AUTHENTICATED -> handle(line, registry);
                }
            }
        } catch (IOException e) {
            log.warn("connection to {} dropped: {}", name, e.getMessage());
        } finally {
            close(registry);
        }
    }

    private void authenticate(String line, ClientRegistry registry) {
        String candidate = line.isBlank() ? "anon" : line;
        if (!registry.addIfAbsent(candidate, this)) {
            send(Message.error("username '" + candidate + "' is already taken"));
            send(Message.system(NAME_PROMPT));
            return;
        }
        name = candidate;
        state = State.AUTHENTICATED;
        log.info("{} joined, {} online", name, registry.size());
        sendHistory();
        registry.broadcast(Message.system(name + " joined"), this);
    }

    private void handle(String line, ClientRegistry registry) {
        Message msg;
        try {
            msg = Protocol.decode(line);
        } catch (ProtocolException e) {
            log.warn("{} sent an unparsable line: {}", name, e.getMessage());
            send(Message.error("malformed message: " + e.getMessage()));
            return;
        }
        if (msg.type() == null) {
            log.warn("{} sent a message with no usable type", name);
            send(Message.error("malformed message: missing or unknown type"));
            return;
        }
        switch (msg.type()) {
            case CHAT -> {
                Message broadcast = Message.broadcast(name, msg.body());
                persist(broadcast);
                registry.broadcast(broadcast, this);
            }
            case USER_LIST -> send(Message.userList(registry.onlineUsers()));
            case QUIT -> state = State.CLOSED;
            default   -> {
                log.warn("{} sent an unexpected type: {}", name, msg.type());
                send(Message.error("unexpected type: " + msg.type()));
            }
        }
    }

    private void sendHistory() {
        List<Message> recent = server.recent().last(HISTORY_SIZE);
        if (recent.isEmpty()) {
            return;
        }
        send(Message.system("last " + recent.size() + " messages"));
        for (Message message : recent) {
            send(message);
        }
    }

    private void persist(Message message) {
        server.recent().add(message);
        MessageWriter writer = server.writer();
        if (writer != null) {
            writer.submit(message);
        }
    }

    private void close(ClientRegistry registry) {
        state = State.CLOSED;
        if (registry.remove(name, this)) {
            log.info("{} left, {} online", name, registry.size());
            registry.broadcast(Message.system(name + " left"), this);
        }
        try {
            socket.close();
        } catch (IOException e) {
            log.warn("failed to close socket for {}: {}", name, e.getMessage());
        }
    }

    String getUsername() {
        return name;
    }

    void disconnect() {
        try {
            socket.close();
        } catch (IOException e) {
            log.warn("failed to close socket for {}: {}", name, e.getMessage());
        }
    }

    void send(Message msg) {
        if (out == null) return;
        try {
            sendRaw(Protocol.encode(msg));
        } catch (ProtocolException e) {
            log.error("dropping unserialisable message to {}", name, e);
        }
    }

    void sendRaw(String line) {
        if (out == null) return;
        out.println(line);
    }
}
