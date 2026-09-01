package com.cli.chat.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cli.chat.common.Message;
import com.cli.chat.common.MessageType;
import com.cli.chat.common.Protocol;
import com.cli.chat.common.User;
import com.cli.chat.common.exception.ProtocolException;
import com.cli.chat.common.exception.StorageException;
import com.cli.chat.common.exception.UsernameTakenException;
import com.cli.chat.db.MessageWriter;
import com.cli.chat.db.UserRepository;

public class ClientHandler implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);

    private static final int HISTORY_SIZE = 20;
    private static final int MAX_LOGIN_ATTEMPTS = 3;
    private static final String NAME_PROMPT = "Enter your name:";
    private static final Set<MessageType> AUTH_TYPES =
            EnumSet.of(MessageType.LOGIN, MessageType.REGISTER);

    private enum State { AWAITING_AUTH, AUTHENTICATED, CLOSED }

    private final Socket socket;
    private final ChatServer server;
    private PrintWriter out;
    private String name = "anon";
    private State state = State.AWAITING_AUTH;
    private int failedLogins;

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
        Message msg = decodeOrNull(line);
        MessageType type = msg == null ? null : msg.type();
        if (type != null && !AUTH_TYPES.contains(type)) {
            log.warn("unauthenticated client sent {}", type);
            send(Message.error("not authenticated: send your name before " + type));
            send(Message.system(NAME_PROMPT));
            return;
        }
        if (type == MessageType.REGISTER) {
            register(msg, registry);
            return;
        }
        if (type == MessageType.LOGIN) {
            login(msg, registry);
            return;
        }
        claimName(line.isBlank() ? "anon" : line, registry);
    }

    private void register(Message msg, ClientRegistry registry) {
        String username = msg.sender();
        String password = msg.body();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            send(Message.error("register requires a username and a password"));
            return;
        }
        UserRepository users = server.users();
        if (users == null) {
            log.error("{} tried to register but no user store is configured", username);
            send(Message.loginFail("registration is unavailable"));
            return;
        }
        if (!registry.addIfAbsent(username, this)) {
            send(Message.loginFail("username '" + username + "' is already online"));
            return;
        }
        try {
            users.create(username, PasswordHasher.hash(password));
        } catch (UsernameTakenException e) {
            registry.remove(username, this);
            log.warn("registration rejected: {}", e.getMessage());
            send(Message.loginFail(e.getMessage()));
            return;
        } catch (StorageException e) {
            registry.remove(username, this);
            log.error("could not register {}", username, e);
            send(Message.loginFail("could not register " + username));
            return;
        }
        send(Message.loginOk(username));
        enterChat(username, registry);
    }

    private void login(Message msg, ClientRegistry registry) {
        String username = msg.sender();
        String password = msg.body();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            send(Message.error("login requires a username and a password"));
            return;
        }
        UserRepository users = server.users();
        if (users == null) {
            log.error("{} tried to log in but no user store is configured", username);
            failLogin("login is unavailable");
            return;
        }
        Optional<User> user;
        try {
            user = users.findByUsername(username);
        } catch (StorageException e) {
            log.error("could not look up {}", username, e);
            failLogin("could not log in " + username);
            return;
        }
        if (user.isEmpty() || !PasswordHasher.matches(password, user.get().passwordHash())) {
            log.warn("login rejected for {}", username);
            failLogin("wrong username or password");
            return;
        }
        if (!registry.addIfAbsent(username, this)) {
            failLogin("username '" + username + "' is already online");
            return;
        }
        send(Message.loginOk(username));
        enterChat(username, registry);
    }

    private void failLogin(String reason) {
        send(Message.loginFail(reason));
        failedLogins++;
        if (failedLogins >= MAX_LOGIN_ATTEMPTS) {
            log.warn("closing a connection after {} failed logins", failedLogins);
            send(Message.error("too many failed logins"));
            state = State.CLOSED;
        }
    }

    private void claimName(String candidate, ClientRegistry registry) {
        if (!registry.addIfAbsent(candidate, this)) {
            send(Message.error("username '" + candidate + "' is already taken"));
            send(Message.system(NAME_PROMPT));
            return;
        }
        enterChat(candidate, registry);
    }

    private void enterChat(String username, ClientRegistry registry) {
        name = username;
        state = State.AUTHENTICATED;
        log.info("{} joined, {} online", name, registry.size());
        sendHistory();
        registry.broadcast(Message.system(name + " joined"), this);
    }

    private Message decodeOrNull(String line) {
        try {
            return Protocol.decode(line);
        } catch (ProtocolException e) {
            return null;
        }
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
