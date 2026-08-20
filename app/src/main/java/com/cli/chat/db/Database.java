package com.cli.chat.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cli.chat.common.exception.StorageException;

public class Database {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    private static final String SCHEMA_RESOURCE = "/schema.sql";

    private final String url;

    private Database(String url) {
        this.url = url;
    }

    public static Database file(String path) {
        return new Database("jdbc:sqlite:" + path);
    }

    public static Database inMemory(String name) {
        return new Database("jdbc:sqlite:file:" + name + "?mode=memory&cache=shared");
    }

    public Connection open() throws StorageException {
        try {
            Connection connection = DriverManager.getConnection(url);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA busy_timeout = 5000");
            }
            return connection;
        } catch (SQLException e) {
            throw new StorageException("could not open " + url, e);
        }
    }

    public void initialise() throws StorageException {
        String schema = readSchema();
        try (Connection connection = open();
             Statement statement = connection.createStatement()) {
            for (String ddl : schema.split(";")) {
                if (!ddl.isBlank()) {
                    statement.execute(ddl);
                }
            }
            log.info("schema applied to {}", url);
        } catch (SQLException e) {
            throw new StorageException("could not apply the schema to " + url, e);
        }
    }

    private String readSchema() throws StorageException {
        try (InputStream in = Database.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new StorageException(SCHEMA_RESOURCE + " is missing from the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException("could not read " + SCHEMA_RESOURCE, e);
        }
    }
}
