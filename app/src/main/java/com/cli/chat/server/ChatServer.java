package com.cli.chat.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.cli.chat.common.Message;

public class ChatServer {

    private static final int DEFAULT_PORT = 5000;

    private final int requestedPort;
    private final ClientRegistry registry = new ClientRegistry();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    private ServerSocket serverSocket;
    private volatile boolean running;
    private int boundPort;

    public ChatServer(int port) {
        this.requestedPort = port;
    }

    ClientRegistry registry() {   // handlers need access to it
        return registry;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(requestedPort);
        boundPort = serverSocket.getLocalPort();
        running = true;
        System.out.println("Server listening on " + boundPort);

        while (running) {
            try {
                Socket socket = serverSocket.accept();
                pool.execute(new ClientHandler(socket, this));
            } catch (IOException e) {
                if (running) throw e;
            }
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();   
            }
        } catch (IOException ignored) {
            // proper handling to be implemented
        }
        pool.shutdown();
    }

    public int getPort() {
        return boundPort;
    }

    void broadcast(Message msg, ClientHandler sender) {
        registry.broadcast(msg, sender);
    }

    void remove(ClientHandler c) {
        registry.remove(c.getUsername());
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        new ChatServer(port).start();
    }
}