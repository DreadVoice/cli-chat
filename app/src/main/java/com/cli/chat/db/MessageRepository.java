package com.cli.chat.db;

import java.util.List;

import com.cli.chat.common.Message;
import com.cli.chat.common.exception.StorageException;

public interface MessageRepository {

    long save(Message message) throws StorageException;

    void saveAll(List<Message> messages) throws StorageException;

    List<Message> recent(int limit) throws StorageException;

    List<Message> recentFor(String recipient, int limit) throws StorageException;
}
