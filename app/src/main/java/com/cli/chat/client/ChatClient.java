package com.cli.chat.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

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
    private static final String USAGE = "usage: ChatClient [host] [port] [--truststore <path>] "
            + "[--truststore-password <password>] [--insecure] [--no-history]";
    private static final String HISTORY_FILE = ".cli-chat-history";
    private static final int HISTORY_SIZE = 500;
    private static final String TRUSTSTORE_PASSWORD_VARIABLE = "CHAT_TRUSTSTORE_PASSWORD";
    private static final String PROMPT = "> ";
    private static final AttributedStyle SYSTEM_STYLE = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);
    private static final AttributedStyle ROSTER_STYLE = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN);
    private static final AttributedStyle ERROR_STYLE = AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);
    private static final AttributedStyle BROADCAST_SENDER = AttributedStyle.DEFAULT.bold();
    private static final AttributedStyle PRIVATE_SENDER =
            AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA).bold();

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
             Terminal terminal = TerminalBuilder.builder().system(true).dumb(true).build()) {

            LineReader console = lineReader(terminal, options.historyFile());

            String username = handshake(in, out, console);
            if (username == null) {
                return;
            }

            Thread reader = new Thread(() -> receiveLoop(in, console));
            reader.setDaemon(true);
            reader.start();

            sendLoop(() -> console.readLine(PROMPT), out, username);
            saveHistory(console);
        }
    }

    static LineReader lineReader(Terminal terminal, Path history) {
        LineReaderBuilder builder = LineReaderBuilder.builder()
                .terminal(terminal)
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .option(LineReader.Option.AUTO_MENU, false)
                .option(LineReader.Option.HISTORY_IGNORE_DUPS, true)
                .option(LineReader.Option.HISTORY_IGNORE_SPACE, true)
                .variable(LineReader.HISTORY_SIZE, HISTORY_SIZE)
                .variable(LineReader.HISTORY_FILE_SIZE, HISTORY_SIZE);
        if (history != null) {
            builder.variable(LineReader.HISTORY_FILE, history);
        }
        return builder.build();
    }

    private static void saveHistory(LineReader console) {
        try {
            console.getHistory().save();
        } catch (IOException e) {
            log.debug("could not save the history", e);
        }
    }

    static Options parse(String[] args) {
        Options options = new Options();
        List<String> positional = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--insecure" -> options.insecure = true;
                case "--no-history" -> options.noHistory = true;
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
        boolean noHistory;

        Path historyFile() {
            return noHistory ? null : Path.of(System.getProperty("user.home"), HISTORY_FILE);
        }
    }

    private static String handshake(BufferedReader in, PrintWriter out,
                                    LineReader console) throws IOException {
        Message prompt = readMessage(in);
        if (prompt != null) render(prompt, console);

        while (true) {
            String name;
            try {
                name = console.readLine(PROMPT);
            } catch (UserInterruptException cancelled) {
                continue;
            } catch (EndOfFileException leaving) {
                return null;
            }
            if (name.isBlank()) {
                name = "anon";
            }
            out.println(name);
            return name;
        }
    }

    private static void receiveLoop(BufferedReader in, LineReader console) {
        try {
            Message msg;
            while ((msg = readMessage(in)) != null) {
                render(msg, console);
            }
        } catch (IOException e) {
            log.debug("read loop ended", e);
            console.printAbove("Disconnected.");
        }
    }

    static void sendLoop(LineSource console, PrintWriter out, String username) {
        while (true) {
            String line;
            try {
                line = console.readLine();
            } catch (UserInterruptException cancelled) {
                continue;
            } catch (EndOfFileException leaving) {
                break;
            }
            if (line.equalsIgnoreCase("/quit")) {
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
        out.println(encode(new Message(
                MessageType.QUIT, username, null, null, System.currentTimeMillis())));
    }

    @FunctionalInterface
    interface LineSource {
        String readLine();
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

    static void render(Message msg, LineReader console) {
        console.printAbove(styled(msg));
    }

    static AttributedString styled(Message msg) {
        return switch (msg.type()) {
            case BROADCAST -> sender(BROADCAST_SENDER, msg);
            case PRIVATE_DELIVERY -> sender(PRIVATE_SENDER, msg);
            case SYSTEM -> new AttributedString(line(msg), SYSTEM_STYLE);
            case USER_LIST, LOGIN_OK -> new AttributedString(line(msg), ROSTER_STYLE);
            case ERROR, LOGIN_FAIL -> new AttributedString(line(msg), ERROR_STYLE);
            default -> new AttributedString(line(msg));
        };
    }

    private static AttributedString sender(AttributedStyle style, Message msg) {
        return new AttributedStringBuilder()
                .styled(style, "[" + msg.sender() + "]")
                .append(" ")
                .append(String.valueOf(msg.body()))
                .toAttributedString();
    }

    static String line(Message msg) {
        return switch (msg.type()) {
            case BROADCAST, PRIVATE_DELIVERY -> "[" + msg.sender() + "] " + msg.body();
            case SYSTEM -> "*** " + msg.body() + " ***";
            case USER_LIST -> "--- online: " + msg.body() + " ---";
            case ERROR -> "!!! " + msg.body();
            default -> String.valueOf(msg.body());
        };
    }
}