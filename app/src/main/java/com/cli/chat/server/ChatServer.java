package com.cli.chat.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cli.chat.common.Message;
import com.cli.chat.common.exception.StorageException;
import com.cli.chat.common.exception.TlsException;
import com.cli.chat.db.Database;
import com.cli.chat.db.MessageRepository;
import com.cli.chat.db.MessageWriter;
import com.cli.chat.db.SqliteMessageRepository;
import com.cli.chat.db.SqliteUserRepository;
import com.cli.chat.db.UserRepository;
import com.cli.chat.net.PlainSocketFactory;
import com.cli.chat.net.SocketFactory;
import com.cli.chat.net.TlsSocketFactory;

public class ChatServer {

    private static final Logger log = LoggerFactory.getLogger(ChatServer.class);

    private static final int DEFAULT_PORT = 5000;
    private static final String DEFAULT_DATABASE = "chat.db";
    private static final String KEYSTORE_PROPERTY = "chat.keystore";
    private static final String KEYSTORE_PASSWORD_PROPERTY = "chat.keystore.password";
    private static final String KEYSTORE_PASSWORD_VARIABLE = "CHAT_KEYSTORE_PASSWORD";
    private static final long POOL_TIMEOUT_SECONDS = 5;
    private static final int CACHE_SIZE = 100;

    private final ServerConfig config;
    private final RecentMessages recent = new RecentMessages(CACHE_SIZE);
    private final ClientRegistry registry = new ClientRegistry();
    private final CommandRegistry commands = new CommandRegistry();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    private ServerSocket serverSocket;
    private volatile boolean running;
    private int boundPort;

    public ChatServer(ServerConfig config) {
        this.config = config;
        commands.register(new HelpCommand());
        commands.register(new HistoryCommand());
        commands.register(new KickCommand());
        commands.register(new ListCommand());
        commands.register(new ShutdownCommand());
        commands.register(new WhisperCommand());
    }

    MessageWriter writer() {
        return config.writer();
    }

    MessageRepository history() {
        return config.history();
    }

    UserRepository users() {
        return config.users();
    }

    RecentMessages recent() {
        return recent;
    }

    ClientRegistry registry() {  
        return registry;
    }

    CommandRegistry commands() {
        return commands;
    }

    boolean isAdmin(String username) {
        return config.isAdmin(username);
    }

    public void start() throws IOException {
        warmCache();
        serverSocket = config.sockets().createServerSocket(config.port());
        boundPort = serverSocket.getLocalPort();
        running = true;
        log.info("server listening on port {}", boundPort);

        while (running) {
            try {
                Socket socket = serverSocket.accept();
                pool.execute(new ClientHandler(socket, this));
            } catch (IOException e) {
                if (running) throw e;
                log.debug("accept interrupted during shutdown", e);
            }
        }
    }

    private void warmCache() {
        MessageRepository history = config.history();
        if (history == null) {
            return;
        }
        try {
            recent.addAll(history.recent(recent.capacity()));
            log.info("recent message cache warmed with {} messages", recent.size());
        } catch (StorageException e) {
            log.error("could not warm the recent message cache", e);
        }
    }

    public void stop() {
        if (!running && serverSocket == null) {
            return;
        }
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log.error("failed to close the server socket", e);
        }
        registry.disconnectAll();
        shutdownPool();
        if (config.writer() != null) {
            config.writer().close();
        }
        log.info("server stopped");
    }

    private void shutdownPool() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(POOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.error("client handlers did not finish within {} s", POOL_TIMEOUT_SECONDS);
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public int getPort() {
        return boundPort;
    }

    void broadcast(Message msg, ClientHandler sender) {
        registry.broadcast(msg, sender);
    }

    void remove(ClientHandler c) {
        registry.remove(c.getUsername(), c);
    }

    public static void main(String[] args) throws IOException, StorageException, TlsException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        String databasePath = args.length > 1 ? args[1] : DEFAULT_DATABASE;
        Set<String> admins = args.length > 2 ? parseAdmins(args[2]) : Set.of();

        Database database = Database.file(databasePath);
        database.initialise();

        SqliteMessageRepository repository = new SqliteMessageRepository(database);
        SqliteUserRepository users = new SqliteUserRepository(database);
        MessageWriter writer = new MessageWriter(repository);
        writer.start();

        ChatServer server = new ChatServer(ServerConfig.onPort(port)
                .withStorage(writer, repository)
                .withUsers(users)
                .withAdmins(admins)
                .withSockets(socketFactory()));
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "shutdown"));
        server.start();
    }

    private static SocketFactory socketFactory() throws TlsException {
        String keystore = System.getProperty(KEYSTORE_PROPERTY);
        if (keystore == null) {
            log.warn("no keystore configured, serving in the clear");
            return new PlainSocketFactory();
        }
        String password = System.getenv(KEYSTORE_PASSWORD_VARIABLE);
        if (password == null) {
            password = System.getProperty(KEYSTORE_PASSWORD_PROPERTY, "");
        }
        return TlsSocketFactory.fromKeystore(keystore, password);
    }

    private static Set<String> parseAdmins(String argument) {
        return Arrays.stream(argument.split(","))
                .map(String::strip)
                .filter(name -> !name.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
}