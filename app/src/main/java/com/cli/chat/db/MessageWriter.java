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
    private static final int MAX_BATCH = 100;
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
        List<Message> batch = new ArrayList<>(MAX_BATCH);
        try {
            while (true) {
                batch.clear();
                batch.add(queue.take());
                queue.drainTo(batch, MAX_BATCH - 1);
                if (persistBatch(batch)) {
                    persistRemaining();
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void persistRemaining() {
        List<Message> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        for (int from = 0; from < remaining.size(); from += MAX_BATCH) {
            persistBatch(remaining.subList(from, Math.min(from + MAX_BATCH, remaining.size())));
        }
    }

    private boolean persistBatch(List<Message> batch) {
        boolean shutdown = false;
        List<Message> persistable = new ArrayList<>(batch.size());
        for (Message message : batch) {
            if (message == POISON) {
                shutdown = true;
            } else {
                persistable.add(message);
            }
        }
        if (!persistable.isEmpty()) {
            persist(persistable);
        }
        return shutdown;
    }

    private void persist(List<Message> batch) {
        try {
            repository.saveAll(batch);
        } catch (StorageException e) {
            log.error("could not persist a batch of {} messages, retrying one by one", batch.size(), e);
            for (Message message : batch) {
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
