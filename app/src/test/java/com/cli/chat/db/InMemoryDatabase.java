package com.cli.chat.db;

import java.sql.Connection;
import java.util.UUID;

import com.cli.chat.common.exception.StorageException;

public class InMemoryDatabase implements AutoCloseable {

    private final Database database;
    private final Connection keepAlive;

    private InMemoryDatabase(Database database, Connection keepAlive) {
        this.database = database;
        this.keepAlive = keepAlive;
    }

    public static InMemoryDatabase create() throws StorageException {
        Database database = Database.inMemory("chat-" + UUID.randomUUID());
        Connection keepAlive = database.open();
        database.initialise();
        return new InMemoryDatabase(database, keepAlive);
    }

    public Database database() {
        return database;
    }

    @Override
    public void close() throws Exception {
        keepAlive.close();
    }
}
