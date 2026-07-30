package com.cli.chat.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.cli.chat.common.Message;

public class ChatServer {

    private static final int DEFAULT_PORT = 5000;

    private final int requestedPort;
    private final Set<ClientHandler> clients = ConcurrentHashMap.newKeySet();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    private ServerSocket serverSocket;
    private volatile boolean running;
    private int boundPort;

    public ChatServer(int port) {
        this.requestedPort = port;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(requestedPort);
        boundPort = serverSocket.getLocalPort();
        running = true;
        System.out.println("Server listening on " + boundPort);

        while (running) {
            try {
                Socket socket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(socket, this);
                clients.add(handler);
                pool.execute(handler);
            } catch (IOException e) {
                if (running) {
                    throw e;            
                }
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
        for (ClientHandler c : clients) {
            if (c != sender) {
                c.send(msg);
            }
        }
    }

    void remove(ClientHandler c) {
        clients.remove(c);
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        new ChatServer(port).start();
    }
}