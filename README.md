# CLI Chat

A multi-client terminal chat server and client in Java. Clients connect over TCP, exchange
newline-delimited JSON messages, and see the recent conversation replayed when they join.
Messages are persisted to SQLite off the hot path.

## Requirements

- Java 21 or newer
- Maven 3.8+

## Running

Build and run the test suite:

```bash
cd app
mvn test
```

Start the server (defaults: port `5000`, database `chat.db` in the working directory). Both
arguments are optional and positional - `<port> <database-path>`:

```bash
mvn -q compile exec:java -Dexec.mainClass=com.cli.chat.server.ChatServer
mvn -q compile exec:java -Dexec.mainClass=com.cli.chat.server.ChatServer -Dexec.args="5000 chat.db"
```

Start a client in another terminal (`<host> <port>`, defaults `localhost 5000`):

```bash
mvn -q compile exec:java -Dexec.mainClass=com.cli.chat.client.ChatClient -Dexec.args="localhost 5000"
```

The client prompts for a name, then relays anything typed as a chat message.

| Input    | Effect                                    |
|----------|-------------------------------------------|
| any text | broadcast to every other connected client |
| `/who`   | print the list of users currently online  |
| `/quit`  | leave and close the connection            |

The server logs to the console via SLF4J/Logback; see
[app/src/main/resources/logback.xml](app/src/main/resources/logback.xml) to change levels.
Client faults (bad JSON, unknown message types, dropped connections) log at `WARN`, server
faults at `ERROR`.

## Architecture

```
client/                 common/                server/                db/
  ChatClient ── TCP ──►  Protocol (JSON)  ──►  ChatServer            Database
                         Message               ├─ ClientRegistry     MessageWriter ──► SQLite
                         MessageType           ├─ RecentMessages     MessageRepository
                         exception/            ├─ ClientHandler      UserRepository
                                               └─ PasswordHasher
```

| Package  | Responsibility                                                          |
|----------|-------------------------------------------------------------------------|
| `common` | Wire format: the `Message` record, `MessageType`, `Protocol`, exceptions |
| `server` | Accept loop, auth, per-client handlers, online registry, in-memory history |
| `db`     | SQLite access, repositories, the asynchronous write queue                |
| `client` | Terminal client                                                          |

### Threading model

- **Accept loop** - `ChatServer.start()` blocks on `accept()` and hands each socket to a
  cached thread pool.
- **One thread per client** - `ClientHandler` owns the socket, authenticates the
  connection, then reads until the client quits or disconnects.
- **One writer thread** - `MessageWriter` drains a bounded queue (10 000) and inserts in
  batches of up to 100 inside a single transaction. `submit` never blocks the broadcaster;
  if the queue is full the message is dropped and logged at `ERROR`.
- **Shared state** - `ClientRegistry` (a `ConcurrentHashMap`) claims usernames with
  `putIfAbsent`, so two clients racing on one name cannot both win. `RecentMessages` is a
  synchronized ring buffer of the last 100 messages.

### Message flow

1. A client sends `CHAT`.
2. The handler builds a `BROADCAST`, adds it to the ring buffer, and submits it to the
   write queue.
3. The message is encoded **once** and written to every other connected socket.
4. The writer thread persists it in the background.

On join, the last 20 messages come from the ring buffer, not the database; a join costs no
disk read. The buffer is warmed from SQLite at start-up, so history survives a restart.

### Shutdown

`ChatServer.stop()`, registered as a JVM shutdown hook, stops accepting, disconnects
clients, waits up to 5 s for handlers to finish, then drains the write queue before the
process exits.

## Protocol

One JSON object per line, UTF-8, newline-terminated. Every message has the same five
fields; `recipient` is `null` for anything not addressed to a single user.

```json
{"type":"CHAT","sender":"alice","recipient":null,"body":"hello","timestamp":1755740000000}
```

| Field       | Type           | Notes                                           |
|-------------|----------------|-------------------------------------------------|
| `type`      | string enum    | see below; unknown values are rejected          |
| `sender`    | string         | username, or `SERVER` for server-generated ones |
| `recipient` | string or null | target username, `null` when broadcast          |
| `body`      | string or null | message text                                    |
| `timestamp` | number         | epoch milliseconds                              |

### Handshake

A connection starts unauthenticated. Until it authenticates, the only types the server
entertains are `LOGIN` and `REGISTER`; anything else is answered with an `ERROR` and a fresh
prompt, and is never taken as a username.

1. Server to client: `SYSTEM` message with body `Enter your name:`
2. Client to server, one of:
   - `REGISTER`, username in `sender` and password in `body`, which creates the account
   - `LOGIN`, the same two fields, checked against the stored hash
   - **a raw line of text** with a name (not JSON), the original handshake, still accepted
3. Server to client: `LOGIN_OK` with the username in `recipient`, or `LOGIN_FAIL` with the
   reason in `body`. The raw name line answers with an `ERROR` and a fresh prompt if the name
   is taken, and with nothing at all when it is accepted.

After a client authenticates, the server replays history and announces the join to everyone
else, whichever of the three routes it took.

`LOGIN` never reveals whether a username exists: an unknown user and a wrong password both
come back as `wrong username or password`. Three failed logins close the socket after a final
`ERROR`; the count is per connection, so reconnecting starts a fresh three.

`REGISTER` refuses a username that is already registered or currently online, and leaves the
stored account untouched when it does.

Passwords are hashed with bcrypt at cost 12 before they reach the database, so a `REGISTER`
or `LOGIN` costs a deliberate few hundred milliseconds.

The bundled `ChatClient` still sends the raw name line; it does not speak `LOGIN` or
`REGISTER` yet.

### Types

**Client to server**

| Type        | Meaning                           | Status      |
|-------------|-----------------------------------|-------------|
| `CHAT`      | broadcast `body` to everyone else | implemented |
| `USER_LIST` | request the online roster         | implemented |
| `QUIT`      | leave                             | implemented |
| `LOGIN`     | authenticate                      | implemented |
| `REGISTER`  | create an account                 | implemented |
| `PRIVATE`   | direct message to `recipient`     | reserved    |
| `COMMAND`   | slash command                     | reserved    |

**Server to client**

| Type               | Meaning                                        | Status      |
|--------------------|------------------------------------------------|-------------|
| `BROADCAST`        | a chat message from `sender`                   | implemented |
| `SYSTEM`           | notice: prompts, joins, leaves, history header | implemented |
| `ERROR`            | rejected input, with a reason in `body`        | implemented |
| `USER_LIST`        | roster, comma-separated in `body`              | implemented |
| `PRIVATE_DELIVERY` | delivery of a direct message                   | reserved    |
| `LOGIN_OK`         | authentication accepted, user in `recipient`   | implemented |
| `LOGIN_FAIL`       | authentication rejected, reason in `body`      | implemented |

Reserved types exist in `MessageType` but are not yet handled; sending one gets an `ERROR`
reply and the connection stays open.

### Errors

A malformed line never drops the connection. The server replies with an `ERROR` and keeps
reading:

```json
{"type":"ERROR","sender":"SERVER","recipient":null,"body":"malformed message: could not parse as JSON","timestamp":1755740000000}
```

## Storage

SQLite via `sqlite-jdbc`; the schema is applied at start-up from
[app/src/main/resources/schema.sql](app/src/main/resources/schema.sql).

- `users` - `id`, `username` (unique), `password_hash`, `created_at`. Written by
  `UserRepository`; the `password_hash` is a bcrypt hash at cost 12, never the password.
- `messages` - `id`, `type`, `sender`, `recipient`, `body`, `timestamp`, indexed on
  `(timestamp DESC, id DESC)` and `(recipient, timestamp DESC, id DESC)` to match the two
  read queries.

Usernames are **case-sensitive**; `alice` and `Alice` are different users, both online and
in the database.

[app/src/test/java/com/cli/chat/db/IndexBenchmark.java](app/src/test/java/com/cli/chat/db/IndexBenchmark.java)
measures the indices against 200 000 rows (run it manually; it is a `main`, not a test):

| Query       | Without indices | With indices |
|-------------|-----------------|--------------|
| `recent`    | 127 ms          | 2 ms         |
| `recentFor` | 36 ms           | 3 ms         |

## Upcoming features

- [x] **Auth** - `LOGIN` / `REGISTER` against the `users` table, bcrypt password hashing at
      cost 12, `UsernameTakenException` wired into the handshake, and three failed logins
      closing the socket.
- [ ] **Auth for the client** - move `ChatClient` onto `LOGIN` / `REGISTER` and retire the
      raw name line, so a name is owned by an account rather than claimed first-come.
- [ ] **Private messaging** - `PRIVATE` to `PRIVATE_DELIVERY` routed through
      `ClientRegistry.find`, with `MessageRepository.recentFor` backing per-user history.
- [ ] **Commands** - a `COMMAND` type and a server-side dispatcher, moving `/who` off its
      current ad-hoc handling and adding `/help`, `/msg`, `/history N`.
- [ ] **TLS** - `SSLServerSocket` with a configurable keystore, so credentials and message
      bodies are not sent in the clear.
- [ ] **CLI** - proper argument parsing for both binaries (flags instead of positional
      arguments), a packaged runnable jar, and a real console UI (`ConsoleUI` is currently
      an empty placeholder).
