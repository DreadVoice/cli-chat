# CLI Chat

A multi-client terminal chat server and client in Java. Clients connect over TCP, exchange
newline-delimited JSON messages, and see the recent conversation replayed when they join.
Accounts live in SQLite with bcrypt-hashed passwords, messages are persisted off the hot path,
and the whole connection can run over TLS.

## Requirements

- Java 21 or newer
- Maven 3.8+

## Build and run

```bash
cd app
mvn test        # run the suite
mvn package     # suite plus target/cli-chat.jar, both halves in one jar
```

### Server

```bash
java -jar target/cli-chat.jar server
java -jar target/cli-chat.jar server --port 5000 --database chat.db --admins root,ops
```

| Flag                             | Default    | Meaning                                    |
|----------------------------------|------------|--------------------------------------------|
| `--port <port>`                  | `5000`     | port to listen on                          |
| `--database <path>`              | `chat.db`  | SQLite file, created if missing            |
| `--admins <a,b>`                 | none       | usernames allowed to run the admin commands|
| `--keystore <path>`              | none       | turns TLS on, see [Transport](#transport)  |
| `--keystore-password <password>` | none       | prefer `CHAT_KEYSTORE_PASSWORD`            |
| `--help`                         |            | print the usage line                       |

Port, database and admins can also be given positionally, `<port> <database> <admins>`, which
is what the older invocations used. A flag wins over the positional it replaces.

### Client

```bash
java -jar target/cli-chat.jar client
java -jar target/cli-chat.jar client chat.example.com 5000 --truststore truststore.p12
```

```
usage: ChatClient [host] [port] [--truststore <path>] [--truststore-password <password>] [--insecure] [--no-history] [--register]
```

Host and port are positional and default to `localhost 5000`.

The client asks for a name and then a password. A password logs in to an existing account,
`--register` creates the account first, and an empty password joins as a guest on the old raw
name line. A refused password asks again rather than dropping the connection.

Once joined, anything typed is a chat message:

| Input    | Effect                                    |
|----------|-------------------------------------------|
| any text | broadcast to every other connected client |
| `/who`   | print the list of users currently online  |
| `/quit`  | leave and close the connection            |

The bundled client does not send `PRIVATE` or `COMMAND` messages, so private messages and the
server commands below need a client that speaks the JSON protocol directly. The terminal it
gives you is described under [CLI frontend](#cli-frontend).

Either half can also be run from sources with
`mvn -q compile exec:java -Dexec.mainClass=com.cli.chat.server.ChatServer -Dexec.args="..."`.

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

| Package  | Responsibility                                                            |
|----------|---------------------------------------------------------------------------|
| `common` | Wire format: the `Message` record, `MessageType`, `Protocol`, exceptions   |
| `net`    | How a socket is made: plain TCP or TLS from a keystore or truststore       |
| `server` | Accept loop, auth, commands, per-client handlers, online registry, history |
| `db`     | SQLite access, repositories, the asynchronous write queue                  |
| `client` | Terminal client: JLine reader, colour, history, status bar                 |

`ServerConfig` holds everything the server needs (port, repositories, admins, socket factory)
and `ServerOptions` parses the command line into it.

### Threading model

- **Accept loop** - `ChatServer.start()` blocks on `accept()` and hands each socket to a
  cached thread pool.
- **One thread per client** - `ClientHandler` owns the socket, authenticates the connection,
  then reads until the client quits or disconnects. Lines longer than 8192 characters are
  dropped with an `ERROR` rather than buffered.
- **One outbound thread per client** - `ClientWriter` holds a 500 line queue, so a broadcast
  never blocks on a slow reader. A client whose queue fills up is disconnected, and a failed
  write is noticed rather than swallowed.
- **One writer thread** - `MessageWriter` drains a bounded queue (10 000) and inserts in
  batches of up to 100 inside a single transaction. If the queue is full the message is
  dropped and logged at `ERROR`.
- **Shared state** - `ClientRegistry` (a `ConcurrentHashMap`) claims usernames with
  `putIfAbsent`, so two clients racing on one name cannot both win. `RecentMessages` is a
  synchronized ring buffer of the last 100 messages.

### Message flow

1. A client sends `CHAT`.
2. The handler builds a `BROADCAST`, adds it to the ring buffer, and submits it to the write
   queue.
3. The message is encoded **once** and queued to every other connected client.
4. The writer thread persists it in the background.

On join, the last 20 messages come from the ring buffer, not the database, so a join costs no
disk read. The buffer is warmed from SQLite at start-up, so history survives a restart.

### Shutdown

`ChatServer.stop()`, registered as a JVM shutdown hook, stops accepting, disconnects clients,
waits up to 5 s for handlers to finish, then drains the write queue before the process exits.
A `SIGTERM` therefore leaves nothing half-written, as long as the supervisor allows about
10 s to stop.

## Transport

Both ends create sockets through `net.SocketFactory`, which returns plain `Socket` and
`ServerSocket` types, so TLS substitutes `SSLSocket` and `SSLServerSocket` without any call
site changing. Without a keystore the server listens in the clear and says so at `WARN`.

### Generating a development certificate

`keytool` ships with the JDK. This makes a self-signed certificate for `localhost`, puts it in
a PKCS12 keystore for the server, then exports it into a truststore for clients:

```bash
keytool -genkeypair -alias chat -keyalg RSA -keysize 2048 -storetype PKCS12 \
        -keystore keystore.p12 -storepass changeit \
        -dname "CN=localhost" -ext "SAN=dns:localhost,ip:127.0.0.1" -validity 365

keytool -exportcert -alias chat -keystore keystore.p12 -storepass changeit -file chat.cer

keytool -importcert -noprompt -alias chat -storetype PKCS12 \
        -keystore truststore.p12 -storepass changeit -file chat.cer
```

The `SAN` matters: clients check the certificate against the host they dialled, so a
certificate for the wrong name is refused even when it is trusted.

### Running with TLS

```bash
export CHAT_KEYSTORE_PASSWORD=changeit
java -jar target/cli-chat.jar server --keystore keystore.p12 --admins root

java -jar target/cli-chat.jar client localhost 5000 --truststore truststore.p12
```

The keystore password is read from `--keystore-password`, then `CHAT_KEYSTORE_PASSWORD`, then
the `chat.keystore.password` system property. Prefer the environment variable: anything on the
command line is visible in the process list. `-Dchat.keystore` still works in place of
`--keystore`. The client takes its truststore password from `--truststore-password` or
`CHAT_TRUSTSTORE_PASSWORD`.

`--insecure` encrypts the connection but checks nothing about the certificate, for throwaway
development certificates. It warns every time it is used and overrides `--truststore`. Do not
use it against anything you care about: an attacker in the middle can present any certificate
and read the whole session.

Connections negotiate TLS 1.3 or 1.2 only, and a TLS client will not fall back to plain text,
so a mismatched pair fails to connect rather than quietly sending credentials in the clear.

## CLI frontend

The client reads through a [JLine](https://github.com/jline/jline3) `LineReader`, so the input
line behaves the way a shell does, and a message arriving while you type is printed above the
prompt with what you had typed redrawn underneath.

| Key      | Effect                                                |
|----------|-------------------------------------------------------|
| up, down | walk back and forward through what you have typed     |
| `Ctrl-C` | throw away the line being typed and start a fresh one |
| `Ctrl-D` | leave, sending `QUIT` first, the same as `/quit`      |

**History.** What you type is kept in `~/.cli-chat-history`, capped at 500 entries. This is a
chat client, so that file holds messages, not only commands: start a line with a space and it
is never recorded, or run with `--no-history` and nothing is written to disk. Recall within the
session works either way.

**Colour.** A terminal that cannot do colour is sent plain text instead, with no change to the
wording.

| Message                | Look                                   |
|------------------------|----------------------------------------|
| chat from someone      | the `[sender]` prefix bold             |
| private delivery       | the `[sender]` prefix magenta and bold |
| notices                | cyan                                   |
| roster, login accepted | green                                  |
| errors, login refused  | red                                    |

**Status bar.** The bottom line shows the connection state, your name and how many people are
online, for example `connected  alice  3 online`. The count comes from the roster, which the
client re-requests whenever somebody joins or leaves; those replies update the bar silently, so
the only roster you see printed is one you asked for. A terminal without cursor addressing gets
no bar.

## Protocol

One JSON object per line, UTF-8, newline-terminated. Every message has the same five fields;
`recipient` is `null` for anything not addressed to a single user.

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

A connection starts unauthenticated. Until it authenticates the server entertains only `LOGIN`
and `REGISTER`; anything else is answered with an `ERROR` and a fresh prompt, and is never
taken as a username.

1. Server to client: `SYSTEM` message with body `Enter your name:`
2. Client to server, one of:
   - `REGISTER`, username in `sender` and password in `body`, which creates the account
   - `LOGIN`, the same two fields, checked against the stored hash
   - **a raw line of text** with a name (not JSON), the original handshake, still accepted
3. Server to client: `LOGIN_OK` with the username in `recipient`, or `LOGIN_FAIL` with the
   reason in `body`. The raw name line is answered only when it is refused, with an `ERROR`
   and a fresh prompt.

Once authenticated, by any of the three routes, the server replays history and announces the
join to everyone else.

`LOGIN` never reveals whether a username exists: an unknown user and a wrong password both
come back as `wrong username or password`. Three failed logins close the socket after a final
`ERROR`, counted per connection. `REGISTER` refuses a name that is already registered or
currently online, leaving any stored account untouched. Passwords are bcrypt hashed at cost 12
before they reach the database, so either costs a deliberate few hundred milliseconds.

The raw name line is for guests only: a name belonging to an account is refused with a note to
log in instead, so registering a name protects it.

### Usernames

At most 24 characters, made of letters, digits, `.`, `_` and `-`. Commas and spaces are out
because the roster is comma separated and `/whisper` splits its target on whitespace, and
`SERVER` is reserved so nobody can imitate the server. Names are **case-sensitive**: `alice`
and `Alice` are different accounts. A blank name line joins as `anon`.

### Types

**Client to server**

| Type        | Meaning                           |
|-------------|-----------------------------------|
| `CHAT`      | broadcast `body` to everyone else |
| `USER_LIST` | request the online roster         |
| `QUIT`      | leave                             |
| `LOGIN`     | authenticate                      |
| `REGISTER`  | create an account                 |
| `PRIVATE`   | direct message to `recipient`     |
| `COMMAND`   | slash command, the line in `body` |

**Server to client**

| Type               | Meaning                                        |
|--------------------|------------------------------------------------|
| `BROADCAST`        | a chat message from `sender`                   |
| `SYSTEM`           | notice: prompts, joins, leaves, history header |
| `ERROR`            | rejected input, with a reason in `body`        |
| `USER_LIST`        | roster, comma-separated in `body`              |
| `PRIVATE_DELIVERY` | delivery of a direct message                   |
| `LOGIN_OK`         | authentication accepted, user in `recipient`   |
| `LOGIN_FAIL`       | authentication rejected, reason in `body`      |

Every type is handled. A client that sends one of the server-to-client types gets an `ERROR`
naming it, and the connection stays open.

### Private messages

`PRIVATE` carries the target in `recipient` and the text in `body`. The server looks the target
up in `ClientRegistry`, sends them a `PRIVATE_DELIVERY`, and echoes the same message back to
the sender, so both sides see it. A message addressed to yourself arrives once.

When the target is not connected nothing is delivered and the sender is told which case it is:
`user 'bob' is offline` when the account exists, `no such user 'bob'` when it does not, and
`user 'bob' is not online` when the server has no user store to ask. Nothing is queued for
later.

### Commands

`COMMAND` carries the whole command line in `body`, with or without the leading slash. The
server splits the name from the arguments on the first run of whitespace, looks the name up in
a `CommandRegistry` (case-insensitive), and answers an unknown name with an `ERROR` suggesting
`/help`. Commands run only after the connection has authenticated.

| Command                     | Effect                                          | Who    |
|-----------------------------|-------------------------------------------------|--------|
| `/help`                     | list every command with its usage               | anyone |
| `/list`                     | the roster of users currently online            | anyone |
| `/history [count]`          | replay the last `count` messages, 20 by default | anyone |
| `/whisper <user> <message>` | a private message, the same path as `PRIVATE`   | anyone |
| `/kick <user>`              | disconnect a user, telling them who kicked them | admin  |
| `/shutdown`                 | tell everyone, then stop the server             | admin  |

Admin names come from `--admins`. The flag is set on a connection only when it authenticates
with `LOGIN`, so claiming an admin name as a guest grants nothing, and neither does registering
one: the password has to check out against the stored hash first. A non-admin asking for
`/kick` or `/shutdown` gets an `ERROR` and nothing happens.

### Errors

A malformed line never drops the connection. The server replies with an `ERROR` and keeps
reading:

```json
{"type":"ERROR","sender":"SERVER","recipient":null,"body":"malformed message: could not parse as JSON","timestamp":1755740000000}
```

## Storage

SQLite via `sqlite-jdbc`; the schema is applied at start-up from
[app/src/main/resources/schema.sql](app/src/main/resources/schema.sql).

- `users` - `id`, `username` (unique), `password_hash`, `created_at`. The `password_hash` is a
  bcrypt hash at cost 12, never the password.
- `messages` - `id`, `type`, `sender`, `recipient`, `body`, `timestamp`, indexed on
  `(timestamp DESC, id DESC)` and `(recipient, timestamp DESC, id DESC)` to match the two read
  queries.

[app/src/test/java/com/cli/chat/db/IndexBenchmark.java](app/src/test/java/com/cli/chat/db/IndexBenchmark.java)
measures those indices against 200 000 rows (run it manually; it is a `main`, not a test):

| Query       | Without indices | With indices |
|-------------|-----------------|--------------|
| `recent`    | 127 ms          | 2 ms         |
| `recentFor` | 36 ms           | 3 ms         |

## Status

Everything on the original roadmap is built: accounts with bcrypt and rate-limited logins,
private messaging, a server-side command dispatcher with admin commands, TLS on both ends, and
a JLine frontend packaged with the server into one runnable jar.

Known gaps, in rough order of how much they would matter in use:

- The bundled client cannot send `PRIVATE` or `COMMAND`, so `/whisper`, `/help`, `/history`,
  `/kick` and `/shutdown` need a JSON-speaking client.
- Private messages are never stored, so `MessageRepository.recentFor` backs no history and a
  message to somebody offline is refused rather than kept.
- Names nobody has registered are still first-come, and the server serves in the clear unless a
  keystore is configured.
- Clients are not asked for certificates of their own, and `USER_LIST` still answers `/who`
  alongside the newer `/list`.
- One host only: SQLite plus a thread per client means scaling up rather than out, and two
  servers cannot share a database.
