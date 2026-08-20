package com.cli.chat.db;

import java.util.Optional;

import com.cli.chat.common.User;
import com.cli.chat.common.exception.StorageException;
import com.cli.chat.common.exception.UsernameTakenException;

public interface UserRepository {

    User create(String username, String passwordHash) throws StorageException, UsernameTakenException;

    Optional<User> findByUsername(String username) throws StorageException;

    boolean exists(String username) throws StorageException;
}
