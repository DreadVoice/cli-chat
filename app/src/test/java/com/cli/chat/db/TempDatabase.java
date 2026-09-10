package com.cli.chat.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import com.cli.chat.common.exception.StorageException;

public class TempDatabase implements AutoCloseable {

    private final Database database;
    private final Path directory;

    private TempDatabase(Database database, Path directory) {
        this.database = database;
        this.directory = directory;
    }

    public static TempDatabase create() throws StorageException {
        try {
            Path directory = Files.createTempDirectory("cli-chat-");
            Database database = Database.file(directory.resolve("chat.db").toString());
            database.initialise();
            return new TempDatabase(database, directory);
        } catch (IOException e) {
            throw new StorageException("could not create a temporary database", e);
        }
    }

    public Database database() {
        return database;
    }

    @Override
    public void close() throws Exception {
        try (Stream<Path> files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("could not delete " + path, e);
                }
            });
        }
    }
}
