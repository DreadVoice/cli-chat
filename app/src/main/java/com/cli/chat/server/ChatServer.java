package com.cli.chat.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cli.chat.common.Message;
import com.cli.chat.common.exception.StorageException;
import com.cli.chat.db.Database;
import com.cli.chat.db.MessageRepository;
import com.cli.chat.db.MessageWriter;
import com.cli.chat.db.SqliteMessageRepository;

public class ChatServer {

    private static final Logger log = LoggerFactory.getLogger(ChatServer.class);

    private static final int DEFAULT_PORT = 5000;
    private static final String DEFAULT_DATABASE = "chat.db";
    private static final long POOL_TIMEOUT_SECONDS = 5;

    private final int requestedPort;
    private final MessageWriter writer;
    private final MessageRepository history;
    private final ClientRegistry registry = new ClientRegistry();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    private ServerSocket serverSocket;
    private volatile boolean running;
    private int boundPort;

    public ChatServer(int port) {
        this(port, null, null);
    }

    public ChatServer(int port, MessageWriter writer) {
        this(port, writer, null);
    }

    public ChatServer(int port, MessageWriter writer, MessageRepository history) {
        this.requestedPort = port;
        this.writer = writer;
        this.history = history;
    }

    MessageWriter writer() {
        return writer;
    }

    MessageRepository history() {
        return history;
    }

    ClientRegistry registry() {  
        return registry;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(requestedPort);
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
        if (writer != null) {
            writer.close();
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

    public static void main(String[] args) throws IOException, StorageException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        String databasePath = args.length > 1 ? args[1] : DEFAULT_DATABASE;

        Database database = Database.file(databasePath);
        database.initialise();

        SqliteMessageRepository repository = new SqliteMessageRepository(database);
        MessageWriter writer = new MessageWriter(repository);
        writer.start();

        ChatServer server = new ChatServer(port, writer, repository);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "shutdown"));
        server.start();
    }
}