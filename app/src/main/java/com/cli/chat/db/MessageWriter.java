package com.cli.chat.db;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cli.chat.common.Message;
import com.cli.chat.common.MessageType;
import com.cli.chat.common.exception.StorageException;

public class MessageWriter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MessageWriter.class);

    private static final int DEFAULT_CAPACITY = 10_000;
    private static final long SHUTDOWN_TIMEOUT_MS = 5_000;
    private static final Message POISON =
            new Message(MessageType.SYSTEM, "SERVER", null, null, 0L);

    private final MessageRepository repository;
    private final BlockingQueue<Message> queue;
    private final Thread writer;
    private final AtomicLong dropped = new AtomicLong();

    private volatile boolean running;

    public MessageWriter(MessageRepository repository) {
        this(repository, DEFAULT_CAPACITY);
    }

    public MessageWriter(MessageRepository repository, int capacity) {
        this.repository = repository;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.writer = new Thread(this::drain, "message-writer");
        this.writer.setDaemon(true);
    }

    public void start() {
        running = true;
        writer.start();
    }

    public boolean submit(Message message) {
        if (!running) {
            return false;
        }
        if (queue.offer(message)) {
            return true;
        }
        long total = dropped.incrementAndGet();
        log.error("persist queue is full, dropped {} messages so far", total);
        return false;
    }

    public long droppedCount() {
        return dropped.get();
    }

    @Override
    public void close() {
        if (!running) {
            return;
        }
        running = false;
        try {
            queue.put(POISON);
            writer.join(SHUTDOWN_TIMEOUT_MS);
            if (writer.isAlive()) {
                log.error("message writer did not finish within {} ms", SHUTDOWN_TIMEOUT_MS);
                writer.interrupt();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void drain() {
        try {
            while (true) {
                Message message = queue.take();
                if (message == POISON) {
                    persistRemaining();
                    return;
                }
                persist(message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void persistRemaining() {
        List<Message> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        for (Message message : remaining) {
            if (message != POISON) {
                persist(message);
            }
        }
    }

    private void persist(Message message) {
        try {
            repository.save(message);
        } catch (StorageException e) {
            log.error("could not persist a {} from {}", message.type(), message.sender(), e);
        }
    }

    public boolean awaitEmpty(long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!queue.isEmpty()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
    }
}
