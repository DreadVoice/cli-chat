package com.cli.chat.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cli.chat.common.Message;
import com.cli.chat.common.MessageType;
import com.cli.chat.common.Protocol;
import com.cli.chat.common.exception.ProtocolException;
import com.cli.chat.common.exception.TlsException;
import com.cli.chat.net.PlainSocketFactory;
import com.cli.chat.net.SocketFactory;
import com.cli.chat.net.TlsSocketFactory;

public class ChatClient {

    private static final Logger log = LoggerFactory.getLogger(ChatClient.class);

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 5000;
    private static final String USAGE =
            "usage: ChatClient [host] [port] [--truststore <path>] [--truststore-password <password>] [--insecure]";
    private static final String TRUSTSTORE_PASSWORD_VARIABLE = "CHAT_TRUSTSTORE_PASSWORD";

    public static void main(String[] args) throws IOException, TlsException {
        Options options = parse(args);
        if (options == null) {
            System.out.println(USAGE);
            return;
        }

        SocketFactory sockets = sockets(options);

        try (Socket socket = sockets.createSocket(options.host, options.port);
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

    static Options parse(String[] args) {
        Options options = new Options();
        List<String> positional = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--insecure" -> options.insecure = true;
                case "--truststore" -> {
                    if (++i == args.length) return null;
                    options.truststore = args[i];
                }
                case "--truststore-password" -> {
                    if (++i == args.length) return null;
                    options.truststorePassword = args[i];
                }
                default -> positional.add(args[i]);
            }
        }
        if (positional.size() > 2) {
            return null;
        }
        if (!positional.isEmpty()) {
            options.host = positional.get(0);
        }
        if (positional.size() == 2) {
            try {
                options.port = Integer.parseInt(positional.get(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return options;
    }

    private static SocketFactory sockets(Options options) throws TlsException {
        if (options.insecure) {
            if (options.truststore != null) {
                log.warn("--insecure was given, ignoring the truststore");
            }
            return TlsSocketFactory.insecure();
        }
        if (options.truststore == null) {
            return new PlainSocketFactory();
        }
        String password = options.truststorePassword;
        if (password == null) {
            password = System.getenv(TRUSTSTORE_PASSWORD_VARIABLE);
        }
        return TlsSocketFactory.fromTruststore(options.truststore, password);
    }

    static class Options {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        String truststore;
        String truststorePassword;
        boolean insecure;
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
            log.debug("read loop ended", e);
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
        } catch (ProtocolException e) {
            log.error("server sent an unparsable line of {} chars: {}", line.length(), e.getMessage());
            return Message.system(line);
        }
    }

    private static String encode(Message msg) {
        try {
            return Protocol.encode(msg);
        } catch (ProtocolException e) {
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