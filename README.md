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

Start the server (defaults: port `5000`, database `chat.db` in the working directory, no
admins). All three arguments are optional and positional, `<port> <database-path> <admins>`,
where `<admins>` is a comma-separated list of usernames:

```bash
mvn -q compile exec:java -Dexec.mainClass=com.cli.chat.server.ChatServer
mvn -q compile exec:java -Dexec.mainClass=com.cli.chat.server.ChatServer -Dexec.args="5000 chat.db"
mvn -q compile exec:java -Dexec.mainClass=com.cli.chat.server.ChatServer -Dexec.args="5000 chat.db root,ops"
```

Start a client in another terminal. Host and port stay positional and default to
`localhost 5000`; the TLS flags are covered under [Transport](#transport):

```bash
mvn -q compile exec:java -Dexec.mainClass=com.cli.chat.client.ChatClient -Dexec.args="localhost 5000"
```

```
usage: ChatClient [host] [port] [--truststore <path>] [--truststore-password <password>] [--insecure] [--no-history]
```

The client prompts for a name, then relays anything typed as a chat message.

| Input    | Effect                                    |
|----------|-------------------------------------------|
| any text | broadcast to every other connected client |
| `/who`   | print the list of users currently online  |
| `/quit`  | leave and close the connection            |

These three are all the bundled client can send. Accounts, private messages and the server
commands below are reachable from any client that speaks the JSON protocol directly. The
terminal it gives you is described under [CLI frontend](#cli-frontend).

The server logs to the console via SLF4J/Logback; see
[app/src/main/resources/logback.xml](app/src/main/resources/logback.xml) to change levels.
Client faults (bad JSON, unknown message types, dropped connections) log at `WARN`, server
faults at `ERROR`.

## Architecture

```
client/              common/              net/                server/               db/
  ChatClient ──────►  Protocol (JSON) ──►  SocketFactory ────►  ChatServer           Database
  StatusBar           Message              PlainSocketFactory   ├─ ClientRegistry    MessageWriter ──► SQLite
                      MessageType          TlsSocketFactory     ├─ CommandRegistry   MessageRepository
                      exception/                                ├─ RecentMessages    UserRepository
                                                                ├─ ClientHandler
                                                                └─ PasswordHasher
```

| Package  | Responsibility                                                          |
|----------|-------------------------------------------------------------------------|
| `common` | Wire format: the `Message` record, `MessageType`, `Protocol`, exceptions |
| `net`    | How a socket is made: plain TCP or TLS from a keystore or truststore     |
| `server` | Accept loop, auth, commands, per-client handlers, online registry, history |
| `db`     | SQLite access, repositories, the asynchronous write queue                |
| `client` | Terminal client: JLine reader, colour, history, status bar                |

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

## Transport

Both ends create sockets through `net.SocketFactory`, which returns plain `Socket` and
`ServerSocket` types, so the TLS implementation substitutes `SSLSocket` and `SSLServerSocket`
without any call site changing. Without a keystore the server listens in the clear and says so
at `WARN`.

### Generating a development certificate

`keytool` ships with the JDK. This makes a self-signed certificate valid for `localhost`, puts
it in a PKCS12 keystore for the server, then exports it into a truststore for clients:

```bash
keytool -genkeypair -alias chat -keyalg RSA -keysize 2048 -storetype PKCS12 \
        -keystore keystore.p12 -storepass changeit \
        -dname "CN=localhost" -ext "SAN=dns:localhost,ip:127.0.0.1" -validity 365

keytool -exportcert -alias chat -keystore keystore.p12 -storepass changeit -file chat.cer

keytool -importcert -noprompt -alias chat -storetype PKCS12 \
        -keystore truststore.p12 -storepass changeit -file chat.cer
```

The `SAN` matters: clients check the certificate against the host they dialled, so a
certificate without a matching name is refused even when it is trusted.

### Running with TLS

The server reads the keystore path from a system property and its password from the
environment, since anything passed on the command line is visible in the process list:

```bash
export CHAT_KEYSTORE_PASSWORD=changeit
mvn -q compile exec:java -Dexec.mainClass=com.cli.chat.server.ChatServer \
    -Dchat.keystore=keystore.p12 -Dexec.args="5000 chat.db root"
```

| Setting                       | Where                | Meaning                                  |
|-------------------------------|----------------------|------------------------------------------|
| `chat.keystore`               | system property      | keystore path, TLS is off when unset     |
| `CHAT_KEYSTORE_PASSWORD`      | environment variable | keystore password                        |
| `chat.keystore.password`      | system property      | fallback when the variable is unset      |

The client opts in with `--truststore`, and takes its password from
`CHAT_TRUSTSTORE_PASSWORD` when `--truststore-password` is absent:

```bash
mvn -q compile exec:java -Dexec.mainClass=com.cli.chat.client.ChatClient \
    -Dexec.args="localhost 5000 --truststore truststore.p12 --truststore-password changeit"
```

`--insecure` encrypts the connection but checks nothing about the certificate, which is there
for throwaway certificates during development. It logs a warning every time it is used, and
it overrides `--truststore`. Do not use it against anything you care about: an attacker in the
middle can present any certificate and read the whole session.

Connections are negotiated over TLS 1.3 or 1.2 only. A TLS client will not fall back to plain
text, so a mismatched pair fails to connect rather than quietly sending credentials in the
clear.

## CLI frontend

The client reads through a [JLine](https://github.com/jline/jline3) `LineReader`, so the input
line behaves the way a shell does: arrow keys move within it, the history is recallable, and a
message arriving while you type is printed above the prompt with what you had typed redrawn
underneath, untouched.

### Keys

| Key      | Effect                                                     |
|----------|------------------------------------------------------------|
| up, down | walk back and forward through what you have typed          |
| left, right, home, end | move inside the line being edited            |
| `Ctrl-C` | throw away the line being typed and start a fresh one      |
| `Ctrl-D` | leave, sending `QUIT` first, the same as `/quit`           |

### History

What you type is kept in `~/.cli-chat-history`, capped at 500 entries, so it survives between
sessions. Bear in mind that this is a chat client, so the file holds messages, not only
commands. Two ways out: start a line with a space and it is never recorded, or run the client
with `--no-history` and nothing is written to disk at all. Recall inside the session works
either way.

### Colour

Colour comes from JLine attributed strings, so a terminal that cannot do colour is sent plain
text instead, and nothing about the wording changes.

| Message            | Look                        |
|--------------------|-----------------------------|
| chat from someone  | the `[sender]` prefix bold  |
| private delivery   | the `[sender]` prefix magenta and bold |
| notices            | cyan                        |
| roster, login accepted | green                   |
| errors, login refused | red                      |

### Status bar

The bottom line shows the connection state, the name you are signed in as, and how many people
are online, for example `connected  alice  3 online`. The count comes from the roster: the
client asks for one when it joins and again whenever somebody joins or leaves, and those
replies update the bar without printing a roster line of their own, so a `/who` you typed is
still the only roster you see. A terminal without cursor addressing simply gets no bar.

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
| `PRIVATE`   | direct message to `recipient`     | implemented |
| `COMMAND`   | slash command, the line in `body` | implemented |

**Server to client**

| Type               | Meaning                                        | Status      |
|--------------------|------------------------------------------------|-------------|
| `BROADCAST`        | a chat message from `sender`                   | implemented |
| `SYSTEM`           | notice: prompts, joins, leaves, history header | implemented |
| `ERROR`            | rejected input, with a reason in `body`        | implemented |
| `USER_LIST`        | roster, comma-separated in `body`              | implemented |
| `PRIVATE_DELIVERY` | delivery of a direct message                   | implemented |
| `LOGIN_OK`         | authentication accepted, user in `recipient`   | implemented |
| `LOGIN_FAIL`       | authentication rejected, reason in `body`      | implemented |

Every type in `MessageType` is handled. A client that sends one of the server-to-client
types gets an `ERROR` naming it, and the connection stays open.

### Private messages

`PRIVATE` carries the target in `recipient` and the text in `body`. The server looks the
target up in `ClientRegistry`, sends them a `PRIVATE_DELIVERY`, and echoes the same message
back to the sender, so both sides see it. A message addressed to yourself arrives once.

When the target is not connected, nothing is delivered and the sender is told which case it
is: `user 'bob' is offline` when the account exists, `no such user 'bob'` when it does not,
and `user 'bob' is not online` when the server has no user store to ask. Nothing is queued
for later.

### Commands

`COMMAND` carries the whole command line in `body`, with or without the leading slash. The
server splits the name from the arguments on the first run of whitespace, looks the name up
in a `CommandRegistry` (case-insensitive), and answers an unknown name with an `ERROR`
suggesting `/help`. Commands run only after the connection has authenticated.

| Command                     | Effect                                              | Who    |
|-----------------------------|-----------------------------------------------------|--------|
| `/help`                     | list every command with its usage                   | anyone |
| `/list`                     | the roster of users currently online                | anyone |
| `/history [count]`          | replay the last `count` messages, 20 by default     | anyone |
| `/whisper <user> <message>` | a private message, the same path as `PRIVATE`       | anyone |
| `/kick <user>`              | disconnect a user, telling them who kicked them     | admin  |
| `/shutdown`                 | tell everyone, then stop the server                 | admin  |

### Admins

Admin usernames are configured at start-up, as the third argument to the server. The flag is
set on a connection only when it authenticates with `LOGIN`, so claiming an admin name with
the raw name line grants nothing, and registering an admin name does not either: the password
has to check out against the stored hash first. A non-admin asking for `/kick` or `/shutdown`
gets an `ERROR` and nothing happens.

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
- [x] **Auth for the client** - the server takes `LOGIN` and `REGISTER` from any client that
      speaks them, and still accepts the raw name line; `ChatClient` itself has not moved over.
- [x] **Private messaging** - `PRIVATE` to `PRIVATE_DELIVERY` routed through
      `ClientRegistry.find`, echoed to the sender, with offline and unknown targets told
      apart; `MessageRepository.recentFor` does not back per-user history yet.
- [x] **Commands** - a `COMMAND` type, a server-side dispatcher over a `Command` registry,
      and `/help`, `/list`, `/history`, `/whisper`, plus the admin-only `/kick` and
      `/shutdown`. The `USER_LIST` type still answers `/who` alongside `/list`.
- [x] **TLS** - a `SocketFactory` seam with `SSLServerSocket` from a configurable keystore,
      client truststores with hostname checks, and an `--insecure` fallback for development
      certificates. The server still listens in the clear when no keystore is configured, and
      clients are not asked for certificates of their own.
- [x] **CLI** - a JLine frontend for the client: line editing, history across sessions,
      `Ctrl-C` and `Ctrl-D` handling, colour by message kind, and a status bar. The client
      takes flags alongside its positional host and port; the server still reads positional
      arguments and system properties, and there is no packaged runnable jar.
