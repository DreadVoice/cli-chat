package com.cli.chat.server;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientWriter {

    private static final Logger log = LoggerFactory.getLogger(ClientWriter.class);

    private static final int QUEUE_SIZE = 500;
    private static final long POLL_TIMEOUT_MS = 100;
    private static final long FLUSH_TIMEOUT_MS = 250;

    private final Socket socket;
    private final PrintWriter out;
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
    private final Thread thread;

    private volatile boolean running;

    ClientWriter(Socket socket) throws IOException {
        this.socket = socket;
        this.out = new PrintWriter(socket.getOutputStream(), true);
        this.thread = new Thread(this::drain, "writer-" + socket.getPort());
        this.thread.setDaemon(true);
    }

    void start() {
        running = true;
        thread.start();
    }

    void submit(String line) {
        if (!running) {
            return;
        }
        if (!queue.offer(line)) {
            log.warn("outbound queue for {} is full, dropping the connection", socket.getRemoteSocketAddress());
            running = false;
            closeSocket();
        }
    }

    void close() {
        running = false;
        try {
            thread.join(FLUSH_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        out.flush();
    }

    private void drain() {
        try {
            while (running || !queue.isEmpty()) {
                String line = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (line != null && !write(line)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean write(String line) {
        out.println(line);
        if (out.checkError()) {
            log.warn("could not write to {}, closing the connection", socket.getRemoteSocketAddress());
            running = false;
            closeSocket();
            return false;
        }
        return true;
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException e) {
            log.warn("failed to close the socket for {}: {}", socket.getRemoteSocketAddress(), e.getMessage());
        }
    }
}
