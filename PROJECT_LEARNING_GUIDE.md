# Project Learning Guide

This guide explains the Online Auction System from the point of view of someone
who already knows Java inheritance, polymorphism, abstract classes, interfaces,
generics, and simple data structures.

The project uses those ideas, but its real difficulty comes from other areas:
multi-module Maven builds, JavaFX desktop UI, FXML, TCP sockets, JSON messages,
JDBC/SQLite persistence, layered architecture, concurrency, asynchronous UI
updates, scheduled tasks, password hashing, design patterns, and automated tests.

## 1. What This Project Is

This is a full-stack Java desktop auction application.

It has two running programs:

- `auction-server`: a TCP server that owns the database and business rules.
- `auction-client`: a JavaFX desktop application that users interact with.

It also has one shared library:

- `auction-common`: shared DTOs, request/response classes, and protocol types.

The system lets users:

- Register and log in as a bidder or seller.
- Browse auctions.
- Create items and auctions as a seller.
- Place bids as a bidder.
- Set auto-bids.
- Watch real-time bid updates.
- Let admins view users and ban accounts.

The important architectural idea is that the client does not directly access the
database. The client talks to the server over a persistent TCP socket. The server
validates requests, updates SQLite, and sends responses or live broadcasts.

Why this matters: this is not just an implementation detail. It is the main
security and consistency decision in the project. If every client could connect
to the database directly, every client would need database credentials, every
client would need to duplicate validation rules, and a modified client could try
to update prices or users without going through server-side checks. By forcing
all database access through the server, there is one trusted place where auction
rules are enforced.

## 2. Repository Structure

```text
bai_tap_lon_private/
  pom.xml
  README.md
  Tree.md
  auction.db

  auction-common/
    pom.xml
    src/main/java/com/auction/common/
      dto/
      protocol/
      request/

  auction-server/
    pom.xml
    src/main/java/com/auction/server/
      dao/
      db/
      exception/
      factory/
      model/
      network/
      observer/
      service/
      util/
      ServerMain.java
    src/test/java/com/auction/server/

  auction-client/
    pom.xml
    src/main/java/com/auction/client/
      controller/
      network/
      session/
      util/
      ClientMain.java
    src/main/resources/com/auction/client/
      fxml/
      css/
```

Think of the modules like this:

```text
auction-client  -->  auction-common  <--  auction-server
                                      |
auction-server  -->  SQLite database |
```

The client and server both depend on `auction-common`. The client does not depend
on `auction-server`, and the server does not depend on `auction-client`.

That separation is deliberate. It means the client can only know about safe
shared types such as `UserDTO`, `AuctionDTO`, `Message`, `Requests`, and
`Responses`. It cannot accidentally use server-only objects such as `User` with
`passwordHash`.

### Why The Repository Is Split This Way

The repository is split by responsibility, not just by file type.

`auction-common` exists because the client and server need to agree on the same
network contract. Both sides must know what a `Message` looks like, what
`MessageType.LOGIN` means, and what fields are inside `LoginRequest` or
`AuctionDTO`. Putting these classes in a shared module avoids copying the same
classes into both client and server.

`auction-server` owns trusted logic. It contains domain models, DAOs, SQLite
code, password hashing, bidding rules, scheduling, auto-bid logic, and socket
handlers. The client should not be able to import these classes because they are
server-side implementation details.

`auction-client` owns presentation and user interaction. It contains JavaFX
controllers, FXML screens, CSS, the client socket connection, scene navigation,
and client-side session state. The server should not need JavaFX dependencies.

This structure gives three benefits:

- Clear dependency direction: client and server both depend on common, but not
  on each other.
- Safer boundaries: server-only data such as password hashes cannot accidentally
  leak into client code.
- Easier builds: CI can compile the JavaFX client separately from server tests,
  and the server can run without JavaFX installed as a runtime concern.

### Alternatives To This Structure

Single module:

Putting all classes in one module would be simpler at the start, but the client
could accidentally use server-only classes. It would also mix JavaFX, SQLite,
server networking, tests, and protocol objects in one classpath. That becomes
harder to reason about as the project grows.

Two modules, client and server only:

This avoids one big module, but then shared protocol classes must either be
duplicated or placed in one side and imported by the other. If the client depends
on the server just to use `AuctionDTO`, it also sees server internals. If the
server depends on the client, the server gets UI dependencies it does not need.

Many tiny modules:

For example, separate modules for `domain`, `dao`, `service`, `network`, and
`ui`. That can be useful in large systems, but here it would add Maven complexity
without much benefit. Three modules are enough to enforce the important boundary:
shared protocol, server, and client.

## 3. Maven: The Build System

Maven is the tool that compiles the project, downloads dependencies, runs tests,
and builds runnable jars.

The root [pom.xml](pom.xml) is a parent POM. It has:

```xml
<packaging>pom</packaging>
```

That means the root module is not itself a runnable app. It manages child
modules:

```xml
<modules>
    <module>auction-common</module>
    <module>auction-server</module>
    <module>auction-client</module>
</modules>
```

### Important Maven Concepts

`groupId`, `artifactId`, `version`

These identify a module. For example, the full identity of the common module is:

```text
com.auction:auction-common:1.0.0
```

`dependencyManagement`

The root POM defines dependency versions in one place. Child modules can depend
on `gson`, `junit`, `sqlite-jdbc`, etc. without repeating versions.

`dependencies`

Each child POM declares what it needs.

- `auction-common` needs Gson.
- `auction-server` needs `auction-common`, Gson, SQLite JDBC, SLF4J, JUnit,
  and Mockito.
- `auction-client` needs `auction-common`, Gson, SLF4J, and JavaFX.

`maven-shade-plugin`

The server and client use the Shade plugin to build "fat jars". A fat jar bundles
your compiled classes plus dependency classes so the app can run more easily.

### Useful Commands

Build everything:

```bash
mvn install
```

Run server tests:

```bash
mvn -pl auction-common,auction-server test
```

Start the server after packaging:

```bash
java -jar auction-server/target/auction-server-1.0.0-fat.jar
```

Start the JavaFX client:

```bash
mvn -pl auction-client javafx:run
```

## 4. Runtime Architecture

At runtime, the system looks like this:

```text
Client process                                Server process
--------------                                --------------
JavaFX screens                                ServerSocket on port 9090
Controllers                                   ClientHandler per connected client
ServerConnection                              Services
TCP socket  <------------------------------>  DAOs
                                               SQLite auction.db
```

The normal flow is:

1. `ClientMain` starts JavaFX.
2. `ClientMain` creates `ServerConnection`.
3. `ServerConnection` opens a TCP socket to the server.
4. The user logs in.
5. A controller creates a `Message`.
6. `ServerConnection` serializes the message to JSON and sends it over TCP.
7. `ClientHandler` on the server reads the JSON line.
8. `ClientHandler` dispatches by `MessageType`.
9. A service runs business logic.
10. A DAO reads or writes SQLite.
11. The server sends a response message back.
12. The client completes a `CompletableFuture` and updates the JavaFX UI.

For live bid updates, the server can also send messages without the client first
asking. Those are broadcasts.

## Architectural Decisions In Detail

This section explains why the project was built this way and what alternatives
were possible.

### Decision 1: Client-Server Architecture

Chosen design:

```text
JavaFX client -> TCP protocol -> Java server -> SQLite database
```

The client is not trusted. A user can modify their own client program, inspect
network traffic, or send custom messages. Because of that, important rules must
live on the server:

- whether the user is authenticated.
- whether the user is a bidder, seller, or admin.
- whether the auction is still active.
- whether a bid is higher than the current price.
- whether the leading bidder is trying to bid again.
- whether an admin can ban a user.
- whether an auction should close or extend.

The server is the trusted gatekeeper. The database is behind the server, not
behind the client.

Alternative: client directly accesses SQLite.

This would mean the JavaFX app opens `auction.db` directly. It is bad for this
project because:

- Multiple clients on different machines cannot safely share one local SQLite
  file.
- Every client would need database access.
- Users could modify the client and write illegal database rows.
- Business rules would need to be duplicated in each client.
- Real-time broadcasts would still require some separate communication system.

Alternative: put everything in one desktop app.

A single local app with UI and database in the same process is simpler, but it is
not an online auction system. It cannot naturally support multiple users bidding
against each other from different clients.

Alternative: serverless or cloud database directly from client.

Some modern apps connect clients directly to Firebase or Supabase-like services.
That can work when the backend provides strong security rules and real-time
channels. This Java project is designed to demonstrate server-side Java,
sockets, JDBC, services, DAOs, and concurrency, so implementing the server
yourself is more educational and gives full control over bidding rules.

### Decision 2: TCP Sockets Instead Of REST Polling

Chosen design:

```text
persistent TCP socket + JSON messages + server-push broadcasts
```

An auction is real-time by nature. If one bidder places a bid, every watching
client should see the new price immediately.

With a persistent TCP socket:

- the client connects once.
- the connection stays open.
- the client can send requests anytime.
- the server can send broadcasts anytime.
- there is no need to repeatedly ask "has anything changed?"

This fits `WATCH_AUCTION`, `BID_BROADCAST`, `AUCTION_END_BROADCAST`, and
`AUCTION_EXTENDED`.

Alternative: REST over HTTP with polling.

REST is usually request-response. The client sends an HTTP request, the server
returns an HTTP response, then the request is over. To get live auction updates,
the client would need to poll:

```text
GET /auctions/5
GET /auctions/5
GET /auctions/5
```

every second or every few seconds.

Problems:

- More wasted requests when nothing changes.
- Higher delay if polling interval is too slow.
- More server load if polling interval is fast.
- More complicated UI state because updates arrive only on polling ticks.

Your intuition is correct: REST polling needs constant updating, while TCP lets
the server push events when something actually happens.

Alternative: REST plus WebSocket.

This is a common production design:

```text
REST for normal commands
WebSocket for live updates
```

It is strong, but it introduces two protocols instead of one. For this project,
raw TCP with JSON gives both request-response and push events through one
connection.

Alternative: Server-Sent Events.

Server-Sent Events can push updates from server to browser/client over HTTP, but
they are one-way: server to client. The client still needs normal HTTP requests
for commands. This project benefits from one bidirectional connection.

Alternative: RMI, gRPC, or message brokers.

Java RMI is Java-specific and less transparent than JSON messages. gRPC is
powerful but adds Protocol Buffers and generated code. Message brokers such as
RabbitMQ or Kafka are overkill for a student-scale desktop auction app.

### Decision 3: Raw TCP Instead Of HTTP

Raw TCP means the project uses Java's `Socket` and `ServerSocket` directly. It
does not use an HTTP server, servlet container, Spring MVC, or browser-style
request handling.

The project defines its own simple application protocol:

```text
one JSON Message per line
```

This is why `readLine()` and `println()` are enough for message boundaries.

Why raw TCP is reasonable here:

- Java has built-in socket APIs.
- The protocol is small and easy to inspect.
- The connection is naturally bidirectional.
- Server-push events are straightforward.
- It demonstrates networking fundamentals directly.

Trade-offs:

- You must design your own message format.
- You must handle request IDs yourself.
- You must handle malformed messages yourself.
- You do not get HTTP tooling, routing, status codes, headers, or browser support.
- Firewalls and proxies tend to understand HTTP better than custom TCP ports.

For a production public web API, HTTP/WebSocket would usually be more standard.
For this project's learning goals and real-time desktop requirement, raw TCP is
defensible.

### Decision 4: JSON Instead Of Java Object Serialization

Chosen design:

```text
Java object -> Gson -> JSON text -> TCP -> Gson -> Java object
```

JSON is human-readable. You can log it, inspect it, and debug it.

Alternative: Java built-in object serialization.

Java object serialization can send Java objects over streams, but it is brittle
and unsafe for network protocols:

- Both sides must have compatible Java classes.
- It is harder to inspect manually.
- It has a history of security problems when deserializing untrusted input.
- It locks the protocol tightly to Java.

Alternative: Protocol Buffers, Avro, or MessagePack.

These are more efficient and stricter, but they add schema files, generated code,
or extra tooling. JSON is enough for this project's scale.

### Decision 5: DTOs Instead Of Sending Domain Models

Chosen design:

```text
server domain model -> DtoMapper -> common DTO -> JSON -> client
```

DTOs are used because the network contract should be separate from the server's
internal model.

Benefits:

- `UserDTO` does not expose `passwordHash`.
- `AuctionDTO` can embed `ItemDTO` for convenient display.
- `BidDTO` can include `timestampMillis` for charting.
- The client only depends on stable, safe data structures.
- Server models can change without automatically changing the network protocol.

Alternative: send server domain objects directly.

This is risky because domain objects may contain private or server-only fields.
It also couples the client to the server's internal model. If the server changes
`Auction`, the client might break even if the UI did not need that change.

Alternative: use `Map<String, Object>` everywhere.

This is flexible but unsafe. You lose compile-time checking, autocomplete, and
clear documentation of required fields.

### Decision 6: Layered Server Instead Of All Logic In `ClientHandler`

Chosen design:

```text
ClientHandler -> Service -> DAO -> SQLite
```

`ClientHandler` handles protocol and socket concerns. Services handle business
rules. DAOs handle SQL.

Benefits:

- Each class has a smaller job.
- Business rules can be tested without sockets.
- SQL can be changed without rewriting business rules.
- The server's request dispatch stays understandable.

Alternative: put all logic in `ClientHandler`.

This would be fast to write at first, but it would mix JSON parsing, auth checks,
bidding rules, SQL statements, DTO mapping, and socket writes in one class. It
would become hard to test and hard to modify.

Alternative: use a large framework such as Spring.

Spring would provide dependency injection, controllers, repositories, validation,
and more. That is useful for larger applications, but this project's dependency
graph is small. Manual wiring in `AuctionServer` is simpler and makes the
relationships visible.

### Decision 7: DAO Interfaces Over Direct JDBC In Services

Chosen design:

```text
UserService depends on UserDAO
SQLiteUserDAO implements UserDAO
```

Benefits:

- Services do not contain SQL strings.
- Tests can mock DAO interfaces.
- SQLite could be replaced by another database implementation later.
- The business API is clearer than raw SQL calls.

Alternative: direct JDBC in services.

This reduces the number of files, but it mixes business rules with persistence.
For example, `BidService` is already complex because of locking and auto-bids.
Adding SQL details directly into it would make it much harder to understand.

Alternative: ORM such as Hibernate/JPA.

An ORM maps Java objects to database tables automatically. It can reduce SQL
boilerplate in large systems, but it introduces annotations, lazy loading,
sessions, transactions, and hidden SQL behavior. For this project, handwritten
JDBC is more explicit and easier to trace while learning.

### Decision 8: SQLite Instead Of MySQL/PostgreSQL

Chosen design:

```text
SQLite file database + JDBC
```

SQLite is chosen because it is:

- zero-configuration.
- stored in one file.
- easy to run on a student machine.
- easy to reset for tests.
- enough for one Java server process.

The server is the only process that writes to the database, so SQLite's
single-writer limitation is acceptable here.

Alternative: PostgreSQL or MySQL.

These are stronger production databases. They handle many concurrent writers,
network connections, permissions, indexing, backups, and larger deployments
better. But they require installing and running a database server, configuring
users/passwords, and managing service startup. That is unnecessary for this
assignment-scale app.

Alternative: in-memory collections only.

This would simplify persistence but data would disappear when the server exits.
It would also fail to demonstrate JDBC, SQL, schemas, and integration tests.

Alternative: file storage with JSON or CSV.

This is simple for small data, but weak for relationships. Auctions reference
items and users; bids reference auctions and bidders. SQL handles these
relationships much better.

### Decision 9: Manual Dependency Injection

Chosen design:

```java
SQLiteUserDAO userDAO = new SQLiteUserDAO();
UserService userService = new UserService(userDAO);
```

in `AuctionServer`.

Benefits:

- Easy to see what depends on what.
- No framework setup.
- No annotations required.
- Tests can still inject mocks manually.

Alternative: create dependencies inside each service.

For example, `UserService` could do `new SQLiteUserDAO()` internally. That would
make tests harder because you could not easily replace the DAO with a mock.

Alternative: Spring or Guice dependency injection.

Useful in larger projects, but extra complexity here.

### Decision 10: JavaFX Desktop UI

Chosen design:

```text
JavaFX + FXML + controllers
```

This gives a desktop app with tables, forms, charts, image views, and scene
switching. FXML separates layout from controller logic.

Alternative: console UI.

Much simpler, but poor for auctions because users need lists, forms, countdowns,
bid history, and visual feedback.

Alternative: web frontend.

A web UI with HTML/CSS/JavaScript would be common in production. It would also
make WebSocket a natural choice for live updates. But it would require a web
server/API design and frontend technology outside this Java desktop project.

Alternative: Swing.

Swing is older and still works, but JavaFX has better modern UI controls,
property binding, FXML, and chart support.

### Decision 11: Observer/Event Bus For Broadcasts

Chosen design:

```text
BidService publishes event -> AuctionEventBus -> watching ClientHandlers
```

This decouples bidding logic from network clients.

`BidService` should not need to know:

- how many clients are watching.
- which sockets they use.
- how to serialize broadcast JSON.

It only says "a bid was placed." The event bus handles the fan-out.

Alternative: let `BidService` directly loop through client sockets.

That would tightly couple business logic to networking. It would also make
testing harder and risk slow socket writes blocking bid processing.

### Decision 12: Per-Auction Locks Instead Of Global Lock

Chosen design:

```text
one ReentrantLock per auction
```

This protects bid updates for the same auction while allowing different auctions
to process bids independently.

Alternative: no lock.

Unsafe. Two bids could read the same old price and both be accepted incorrectly.

Alternative: one global lock.

Correct but unnecessarily slow. Bidding on auction 1 would block bidding on
auction 2 even though they are unrelated.

Alternative: rely only on database constraints.

Possible in some systems with transactions and row-level locks, but SQLite's
concurrency model is limited. The explicit Java lock is easier to reason about
here.

### Decision 13: Scheduled Tasks For Auction Closing

Chosen design:

```text
ScheduledExecutorService schedules closeAuction at endTime
```

This means the auction closes even if no client is currently viewing it.

Alternative: close only when a user opens or refreshes the auction.

This is simpler, but stale auctions may remain `RUNNING` long after their end
time until someone happens to view them.

Alternative: database trigger.

SQLite triggers do not run by themselves at a future time. They run when a SQL
operation happens, so they do not solve timed closing alone.

Alternative: external job scheduler.

Cron or a background worker service would be useful in larger deployments, but
too much infrastructure for this project.

### Decision 14: JUnit, Mockito, And Some Real SQLite Tests

Chosen design:

```text
unit tests with mocks + integration tests with temp SQLite
```

Mocks are good for checking business rules quickly. Real SQLite tests are good
for checking SQL and object mapping.

Alternative: only manual testing.

Manual testing is slow and misses regressions.

Alternative: only mock tests.

Fast, but SQL bugs can survive because mocks do not execute SQL.

Alternative: only full integration tests.

More realistic, but slower and harder to isolate when a failure happens.

## 5. `auction-common`: The Shared Protocol

The common module is the shared language between client and server.

The reason this module exists is that protocol classes must be identical on both
sides. If the client thinks `PlaceBidRequest` has fields named `auctionId` and
`amount`, but the server expects `auction_id` and `bidAmount`, communication
breaks. A shared module lets the compiler enforce agreement.

The common module should stay small. It should contain only things both sides
are allowed to know:

- DTOs.
- message envelope.
- message type enum.
- request payloads.
- response payloads.

It should not contain server-only implementation details such as DAOs,
`PasswordUtil`, database schema, or service classes.

### DTOs

DTO means Data Transfer Object.

Files:

- `auction-common/src/main/java/com/auction/common/dto/UserDTO.java`
- `auction-common/src/main/java/com/auction/common/dto/ItemDTO.java`
- `auction-common/src/main/java/com/auction/common/dto/AuctionDTO.java`
- `auction-common/src/main/java/com/auction/common/dto/BidDTO.java`

DTOs are not domain objects. They are simplified objects designed to travel over
the network.

Example: the server-side `User` model has `passwordHash`. `UserDTO` does not.
That is a security boundary. The client should never receive password hashes.

### Message Envelope

File:

```text
auction-common/src/main/java/com/auction/common/protocol/Message.java
```

Every network packet is wrapped in a `Message`.

A message has:

- `requestId`: unique ID used to match a response to its request.
- `type`: a `MessageType` enum value such as `LOGIN` or `PLACE_BID`.
- `payload`: JSON data for that specific message type.

Conceptually:

```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "type": "PLACE_BID",
  "payload": {
    "auctionId": 3,
    "amount": 250.0
  }
}
```

### Why `requestId` Matters

The client has one socket and one background reader thread. Many requests can be
in flight at once.

Example:

1. The UI sends `GET_AUCTION_DETAIL`.
2. The UI sends `GET_BID_HISTORY`.
3. The server may reply in either order.

The client cannot rely on order alone. It uses `requestId`.

`ServerConnection.send()` stores:

```text
requestId -> CompletableFuture<Message>
```

When a response arrives, the reader thread uses the response `requestId` to find
and complete the correct future.

### MessageType

File:

```text
auction-common/src/main/java/com/auction/common/protocol/MessageType.java
```

`MessageType` is the protocol's list of actions.

Examples:

- `LOGIN`
- `REGISTER`
- `GET_AUCTIONS`
- `CREATE_ITEM`
- `CREATE_AUCTION`
- `PLACE_BID`
- `SET_AUTO_BID`
- `WATCH_AUCTION`
- `BID_BROADCAST`
- `AUCTION_END_BROADCAST`
- `ERROR`

Whenever you add a new feature, you normally update:

1. `MessageType`
2. `Requests` or `Responses`
3. Server `ClientHandler`
4. Server service or DAO if needed
5. Client controller

### Requests and Responses

Files:

```text
auction-common/src/main/java/com/auction/common/request/Requests.java
auction-common/src/main/java/com/auction/common/request/Responses.java
```

These contain many small static inner classes.

For example:

- `LoginRequest`
- `RegisterRequest`
- `PlaceBidRequest`
- `SetAutoBidRequest`
- `CreateItemRequest`
- `CreateAuctionRequest`
- `BidResponse`
- `AuctionsResponse`
- `ErrorResponse`

These classes intentionally have public fields and little behavior. They are
wire payloads, not rich domain models.

Why not put validation logic in request classes? Because client-side validation
can improve usability, but server-side validation is the only trusted validation.
A malicious or modified client could still send invalid JSON. The real rules
belong in server services such as `UserService`, `AuctionService`, and
`BidService`.

## 6. JSON and Gson

The project uses Gson to convert Java objects to JSON and JSON back to Java
objects.

Example direction:

```text
PlaceBidRequest Java object
  -> Gson
  -> JSON string
  -> TCP socket
  -> Gson
  -> PlaceBidRequest Java object
```

The project uses:

```java
new GsonBuilder().serializeNulls().create()
```

`serializeNulls()` matters because some fields, such as `winnerId`, are allowed
to be null before any bids exist. If null fields were omitted, the receiving side
would have less predictable JSON.

### Why `JsonElement` Is Used in `Message`

`Message.payload` is a `JsonElement`, not a specific class.

Reason: when Gson first reads the message, it only knows that there is a payload.
It does not yet know whether that payload is a `LoginRequest`, `PlaceBidRequest`,
`BidResponse`, or something else.

After reading `MessageType`, the receiver calls:

```java
PlaceBidRequest req = msg.parsePayload(gson, PlaceBidRequest.class);
```

So the type tells the program how to decode the payload.

## 7. TCP Sockets

This project does not use REST or HTTP. It uses raw TCP sockets.

### What TCP Is

TCP stands for Transmission Control Protocol.

It is a low-level network protocol that gives two programs a reliable ordered
byte stream.

Reliable means TCP handles details such as:

- splitting data into packets.
- resending lost packets.
- keeping packets in order.
- detecting connection loss.

Ordered byte stream means that if one side writes:

```text
hello
world
```

the other side receives the bytes in that same order. TCP does not understand
Java objects, JSON, auctions, users, or bids. It only moves bytes.

### What A TCP Server Is

A TCP server is a program that listens on a port and accepts TCP connections.

In this project:

```text
AuctionServer listens on port 9090
```

The server uses `ServerSocket`. A client uses `Socket` to connect.

When a client connects, the server gets a new `Socket` representing that one
client connection. The original `ServerSocket` keeps listening for more clients.

### What A Raw TCP Socket Is

"Raw TCP socket" means the project uses Java's socket API directly instead of a
higher-level application protocol such as HTTP.

Raw TCP gives only a byte stream. It does not provide:

- URLs.
- HTTP methods such as `GET` or `POST`.
- request headers.
- response status codes like `200` or `404`.
- automatic message boundaries.
- routing such as `/api/auctions/5`.

Because raw TCP does not define message boundaries, this project creates its own
simple rule:

```text
one JSON Message per line
```

That is why the sender uses `println(json)` and the receiver uses `readLine()`.

### What HTTP Is

HTTP stands for Hypertext Transfer Protocol.

It is an application-level protocol built on top of TCP. HTTP defines a standard
request-response format.

Example HTTP request:

```http
GET /auctions/5 HTTP/1.1
Host: localhost:9090
Accept: application/json
```

Example HTTP response:

```http
HTTP/1.1 200 OK
Content-Type: application/json

{"id":5,"currentPrice":250.0}
```

HTTP adds useful conventions:

- methods: `GET`, `POST`, `PUT`, `DELETE`.
- paths: `/users`, `/auctions/5`, `/bids`.
- headers: metadata about the request or response.
- status codes: `200`, `400`, `401`, `404`, `500`.
- broad tool support in browsers, servers, proxies, and debugging tools.

### What REST Is

REST means Representational State Transfer.

REST is not a network protocol by itself. It is a style of designing HTTP APIs.

In a REST-style auction API, you might have:

```text
GET    /auctions          -> list auctions
GET    /auctions/5        -> get auction detail
POST   /auctions          -> create auction
POST   /auctions/5/bids   -> place bid
GET    /auctions/5/bids   -> get bid history
```

REST treats server data as resources identified by URLs. Clients make requests
to read or change those resources.

### Why TCP Fits This Project Better Than Plain REST

Plain REST is naturally request-response. The client asks, the server answers,
and then the exchange is finished.

Auctions need real-time server-push events:

- new bid placed.
- auction extended by anti-sniping.
- auction ended.

With plain REST, the client would usually poll:

```text
GET /auctions/5
wait 1 second
GET /auctions/5
wait 1 second
GET /auctions/5
```

This works, but it is inefficient and less immediate.

If the polling interval is 5 seconds, users may see a bid up to 5 seconds late.
If the polling interval is 0.2 seconds, the server receives many requests even
when no bid has changed.

The TCP design avoids that:

```text
client sends WATCH_AUCTION once
server sends BID_BROADCAST only when a bid actually happens
```

So your reasoning is correct: real-time events are the main reason TCP is chosen
over plain REST polling.

### Why Not REST Plus WebSocket

REST plus WebSocket would also be a good architecture.

Typical production design:

```text
REST:
  login
  list auctions
  create item
  create auction
  place bid

WebSocket:
  live bid updates
  auction ended
  auction extended
```

This is more standard for web apps, but it means the project must implement two
communication styles. The current raw TCP design handles both normal
request-response and server-push broadcasts on one connection.

Important files:

```text
auction-server/src/main/java/com/auction/server/network/AuctionServer.java
auction-server/src/main/java/com/auction/server/network/ClientHandler.java
auction-client/src/main/java/com/auction/client/network/ServerConnection.java
```

### Server Side

`AuctionServer` opens a `ServerSocket`.

It waits for clients:

```java
Socket client = serverSocket.accept();
```

For each client, it creates a `ClientHandler` and runs it in a thread pool.

That means:

- Client A has one `ClientHandler`.
- Client B has another `ClientHandler`.
- They can send requests at the same time.

### Client Side

`ServerConnection` opens one `Socket` to the server.

It has:

- a `PrintWriter` for sending JSON lines.
- a background reader thread for receiving JSON lines.
- a `ConcurrentHashMap` of pending request futures.
- an optional broadcast listener for live auction events.

### Newline-Delimited JSON

Messages are sent as one JSON object per line.

The sender uses `println(json)`.
The receiver uses `readLine()`.

This is simple and effective because JSON itself can contain nested objects, but
the message boundary is the newline.

Without a boundary rule, TCP only gives you a byte stream. It does not preserve
"message objects" for you.

## 8. Asynchronous Programming with `CompletableFuture`

The client cannot block the JavaFX UI thread while waiting for the server.

If it did this:

```java
Message response = conn.sendSync(msg, 5000);
```

inside a button handler, the UI would freeze until the server replied.

Instead the project uses:

```java
conn.send(msg).whenCompleteAsync((resp, ex) -> Platform.runLater(() -> {
    // update UI here
}));
```

Important ideas:

- `send()` returns immediately.
- The UI stays responsive.
- Later, when the server replies, the future completes.
- The callback checks success or error.
- `Platform.runLater()` moves the UI update onto the JavaFX Application Thread.

This is one of the most important concepts in the client.

## 9. JavaFX, FXML, and Controllers

JavaFX is the desktop UI framework.

Important files:

```text
auction-client/src/main/java/com/auction/client/ClientMain.java
auction-client/src/main/java/com/auction/client/controller/
auction-client/src/main/resources/com/auction/client/fxml/
auction-client/src/main/resources/com/auction/client/css/style.css
```

### JavaFX Application Lifecycle

`ClientMain` extends `Application`.

The flow is:

```text
main()
  -> launch(args)
  -> JavaFX starts
  -> start(Stage primaryStage)
```

`Stage` is the operating system window.
`Scene` is the content inside the window.
FXML describes the UI nodes inside the scene.

### What FXML Is

FXML is XML that declares a JavaFX UI.

Instead of writing all UI construction in Java like this:

```java
Button button = new Button("Login");
TextField username = new TextField();
```

FXML lets you define layout in `.fxml` files. Java controllers then handle user
actions.

Each FXML file has a matching controller, for example:

```text
login.fxml              -> LoginController.java
auction_list.fxml       -> AuctionListController.java
auction_detail.fxml     -> AuctionDetailController.java
seller_dashboard.fxml   -> SellerDashboardController.java
admin_panel.fxml        -> AdminController.java
```

### `@FXML`

In controllers, fields like this:

```java
@FXML private TextField bidAmountField;
```

are injected by the FXML loader. The FXML file defines a UI element with the same
`fx:id`, and JavaFX connects it to the controller field.

### The JavaFX Application Thread

JavaFX UI components must be changed only on the JavaFX Application Thread.

This is why you see:

```java
Platform.runLater(() -> labelCountdown.setText(finalText));
```

Background threads are allowed to compute data or read sockets, but UI mutation
must be scheduled back to the FX thread.

If you update labels, tables, or charts from a socket reader thread directly,
you risk random UI bugs or exceptions.

### SceneManager

File:

```text
auction-client/src/main/java/com/auction/client/util/SceneManager.java
```

`SceneManager` centralizes screen switching.

It:

- loads FXML files.
- caches most screens after first load.
- swaps the root node in the current scene.
- calls `refresh()` on controllers that implement `Refreshable`.
- handles auction detail specially because it needs an `auctionId`.

This prevents every controller from duplicating FXML loading and navigation code.

### ClientSession

File:

```text
auction-client/src/main/java/com/auction/client/session/ClientSession.java
```

`ClientSession` is a singleton that stores:

- the shared `ServerConnection`.
- the logged-in `UserDTO`.

Controllers use it to send messages and check roles:

- `isBidder()`
- `isSeller()`
- `isAdmin()`

## 10. Server Layered Architecture

The server is organized into layers.

```text
network layer
  ClientHandler

service layer
  UserService
  ItemService
  AuctionService
  BidService

DAO layer
  UserDAO / SQLiteUserDAO
  ItemDAO / SQLiteItemDAO
  AuctionDAO / SQLiteAuctionDAO
  BidDAO / SQLiteBidDAO
  AutoBidDAO / SQLiteAutoBidDAO

database
  SQLite auction.db
```

Each layer has a job.

The purpose of this layering is to prevent one class from needing to understand
everything. For example, placing a bid touches networking, JSON, authentication,
business rules, database writes, event broadcasts, and DTO conversion. If all of
that lived in one method, the code would be fragile. Layers let each part focus:

- network code translates messages.
- services enforce rules.
- DAOs persist data.
- DTO mappers prepare safe response objects.

This also makes change safer. If you replace SQLite later, most service code can
stay the same. If you redesign the JavaFX screen, server services do not care.

### Network Layer

The network layer knows about sockets, JSON messages, request IDs, and
`MessageType`.

Main class:

```text
auction-server/src/main/java/com/auction/server/network/ClientHandler.java
```

`ClientHandler`:

- reads one JSON line at a time.
- converts JSON to `Message`.
- switches on `MessageType`.
- parses the payload.
- calls a service.
- maps domain objects to DTOs.
- sends a reply message.
- subscribes/unsubscribes clients to auction broadcasts.

### Service Layer

The service layer knows business rules.

Examples:

- Only sellers can create items.
- Only bidders can place bids.
- A bid must exceed the current price.
- The leading bidder cannot bid again.
- Admins can ban users.
- Auctions close at their end time.
- Late bids can trigger anti-sniping extension.

Services should not know about FXML, JavaFX, sockets, JSON line reading, or SQL
string details.

### DAO Layer

DAO means Data Access Object.

The DAO layer knows SQL and JDBC.

Services depend on DAO interfaces, not directly on SQLite implementation classes.

For example:

```java
public final class UserService {
    private final UserDAO userDAO;

    public UserService(UserDAO userDAO) {
        this.userDAO = userDAO;
    }
}
```

This makes testing easier. `UserServiceTest` can pass a Mockito mock `UserDAO`
instead of a real SQLite DAO.

DAO interfaces are chosen over direct JDBC in services because services should
describe business actions in domain language:

```text
find user by username
save bid
update current price
find active auto-bids
```

not low-level SQL mechanics. The SQL still exists, but it is isolated in
`SQLite*DAO` classes.

## 11. SQLite and JDBC

SQLite is a file-based database. In this project, the database file is:

```text
auction.db
```

The server uses JDBC to talk to SQLite.

### Why SQLite Was Chosen

SQLite is a practical choice for this project because the database is local to
one server process. You do not need to install PostgreSQL or MySQL, create users,
open database ports, or run a separate database service.

That matters for a student project because setup friction can become larger than
the programming problem itself. With SQLite, the server can create or open
`auction.db` automatically.

SQLite is not chosen because it is the strongest possible database. It is chosen
because it is enough for this scope:

- one server process.
- moderate data volume.
- simple deployment.
- easy testing with temporary files.
- standard SQL tables and relationships.

For a production auction platform with many servers and heavy traffic,
PostgreSQL or MySQL would usually be better.

### Why JDBC Was Chosen

JDBC is Java's standard database API. It is lower-level than an ORM, but it makes
the actual SQL visible.

This project uses JDBC because:

- it teaches how Java talks to relational databases.
- it keeps dependencies small.
- it makes queries and updates explicit.
- it works directly with SQLite through `sqlite-jdbc`.
- it is easy to use inside DAO classes.

The main trade-off is that JDBC requires more boilerplate than an ORM. You write
SQL, prepare statements, read result sets, and map rows manually. For this
project, that explicitness is useful because you can trace exactly what happens.

### Why Not An ORM

An ORM such as Hibernate/JPA could map Java objects to tables automatically.

That is useful in large business applications, but it adds concepts that are not
needed here:

- entity annotations.
- persistence context.
- lazy loading.
- cascading.
- generated SQL.
- transaction/session lifecycle.

The server's persistence needs are small and direct. Handwritten DAO methods are
easier to follow.

Important file:

```text
auction-server/src/main/java/com/auction/server/db/DatabaseManager.java
```

### DatabaseManager

`DatabaseManager` is responsible for:

- loading the SQLite JDBC driver.
- opening the database connection.
- enabling foreign keys.
- creating tables if they do not exist.
- seeding the default admin account.
- allowing tests to use a temporary database file.

### Tables

The schema creates these tables:

- `users`
- `items`
- `auctions`
- `bid_transactions`
- `auto_bids`

Important relationships:

- `items.seller_id` references `users.id`
- `auctions.item_id` references `items.id`
- `auctions.seller_id` references `users.id`
- `auctions.winner_id` references `users.id`
- `bid_transactions.auction_id` references `auctions.id`
- `bid_transactions.bidder_id` references `users.id`
- `auto_bids.auction_id` references `auctions.id`
- `auto_bids.bidder_id` references `users.id`

### JDBC Concepts

`Connection`

Represents the database connection.

`PreparedStatement`

Represents SQL with placeholders:

```sql
SELECT * FROM users WHERE username = ?
```

The code then sets parameter values:

```java
ps.setString(1, username);
```

This is safer than building SQL strings manually because it avoids SQL injection
and handles quoting correctly.

`ResultSet`

Represents rows returned by a query.

Typical pattern:

```java
ResultSet rs = ps.executeQuery();
while (rs.next()) {
    String username = rs.getString("username");
}
```

`try-with-resources`

DAO methods use try-with-resources so statements and result sets are closed
automatically.

### Why DAO Methods Are `synchronized`

The server shares one SQLite connection. Multiple client threads can access DAOs
at the same time.

SQLite can handle this at small scale, but using `synchronized` DAO methods keeps
database access simple and predictable.

This is not the same as bid correctness. Bid correctness is handled by
per-auction locks in `BidService`.

## 12. Domain Model

The server model package contains the business objects:

```text
auction-server/src/main/java/com/auction/server/model/
```

You already know inheritance and polymorphism, so focus on how the model supports
the rest of the system.

### Users

`User` is abstract.

Concrete types:

- `Bidder`
- `Seller`
- `Admin`

Each role answers:

- `getRole()`
- `canBid()`
- `canSell()`

The server can call `user.canBid()` without checking every role manually.

### Items

`Item` is abstract.

Concrete item types:

- `Electronics`
- `Art`
- `Vehicle`

The project stores category-specific details in `extraData`, a text field that
can contain JSON. This avoids creating many database columns for fields that only
apply to one category.

### Auctions

`Auction` connects:

- an item.
- a seller.
- a price.
- a start time.
- an end time.
- a status.
- a leading bidder.

Status values:

- `OPEN`
- `RUNNING`
- `FINISHED`
- `PAID`
- `CANCELED`

Several fields in `Auction` are `volatile`, such as current price, end time, and
leading bidder fields. `volatile` is a concurrency visibility feature. It helps
one thread see another thread's latest writes.

Important: `volatile` does not make multi-step operations safe. A check-then-set
operation still needs a lock.

## 13. Factories

Files:

```text
auction-server/src/main/java/com/auction/server/factory/UserFactory.java
auction-server/src/main/java/com/auction/server/factory/ItemFactory.java
```

Factories centralize object creation.

Example idea:

```java
User user = UserFactory.create(UserRole.BIDDER);
```

instead of scattering this everywhere:

```java
if (role == BIDDER) return new Bidder();
if (role == SELLER) return new Seller();
if (role == ADMIN) return new Admin();
```

Factories are especially useful when reconstructing objects from database rows.
SQLite stores category and role as strings. The DAO reads the string and asks the
factory to create the right subclass.

## 14. DTO Mapping

File:

```text
auction-server/src/main/java/com/auction/server/util/DtoMapper.java
```

`DtoMapper` converts server domain objects to common DTO objects.

Examples:

```text
User           -> UserDTO
Item           -> ItemDTO
Auction        -> AuctionDTO
BidTransaction -> BidDTO
```

This layer matters because the server model is not the same as the network
contract.

### Why DTOs Are An Architectural Boundary

DTOs are chosen because the data that travels over the network should be
deliberately designed. The server's internal classes are allowed to contain
implementation details. The client's classes should receive only what the UI is
allowed to know.

Without DTOs, it becomes too easy to expose fields accidentally. For example,
sending a full `User` object could expose `passwordHash`. Sending a full
`Auction` object could expose server-only state or Java types that are awkward
for the client to parse.

DTOs also let the server shape data for the UI. `AuctionDTO` embeds `ItemDTO`
because the client usually wants auction and item details together. `BidDTO`
contains both a formatted timestamp and `timestampMillis` because the table and
chart need different timestamp forms.

Alternative: send domain models directly.

This couples client and server too tightly and risks leaking private fields.

Alternative: send untyped JSON maps.

This is flexible, but the compiler cannot help you. A typo in a field name may
only fail at runtime.

Alternative: create separate DTO classes for every screen.

This can be useful in bigger systems, but it would create too many nearly
identical classes here. The current DTOs are broad enough for this app without
being unsafe.

Example:

- `User` has `passwordHash`.
- `UserDTO` does not.

Example:

- `Auction` contains `LocalDateTime`.
- `AuctionDTO` stores date/time as strings.

Example:

- `BidTransaction` stores `LocalDateTime`.
- `BidDTO` also includes `timestampMillis` so the JavaFX chart can use a numeric
  x-axis.

## 15. Observer Pattern and Real-Time Updates

Files:

```text
auction-server/src/main/java/com/auction/server/observer/AuctionObserver.java
auction-server/src/main/java/com/auction/server/observer/AuctionEventBus.java
```

This is how real-time updates work.

### The Problem

If bidder A places a bid, every other client watching that auction should see the
new price immediately.

The client should not need to ask:

```text
Has the price changed?
Has the price changed?
Has the price changed?
```

every second.

### The Solution

The client sends:

```text
WATCH_AUCTION
```

The server registers that client's `ClientHandler` as an observer for that
auction.

When a bid happens:

```text
BidService
  -> AuctionEventBus.publishBidPlaced(...)
  -> ClientHandler.onBidPlaced(...)
  -> send BID_BROADCAST to client
  -> client AuctionDetailController updates UI
```

### Why Notifications Are Asynchronous

The event bus uses a thread pool for notifications.

Reason: a slow client socket should not block the bidding engine.

If the server wrote broadcasts synchronously while holding the bid lock, then one
slow client could delay every other bid on the auction.

## 16. Concurrency

This is one of the most important parts of the project outside basic Java.

Concurrency means multiple things can happen at the same time.

This project has many threads:

Server:

- main server accept loop.
- one `ClientHandler` thread per client.
- auction scheduler thread.
- event notification pool threads.

Client:

- JavaFX Application Thread.
- socket reader thread.
- timer thread for countdowns.

### Race Condition Example

Suppose current price is 100.

Two clients bid at the same time:

```text
Thread A reads currentPrice = 100
Thread B reads currentPrice = 100
Thread A accepts bid 110
Thread B accepts bid 105
```

Without locking, the final result could be wrong.

This is called a lost update.

### Per-Auction `ReentrantLock`

File:

```text
auction-server/src/main/java/com/auction/server/service/BidService.java
```

`BidService` stores:

```java
ConcurrentHashMap<Long, ReentrantLock> auctionLocks
```

There is one lock per auction.

This means:

- Two bids on the same auction are serialized.
- Bids on different auctions can happen at the same time.

That is better than one global lock, which would make unrelated auctions block
each other.

The lock is created lazily:

```java
auctionLocks.computeIfAbsent(auctionId, k -> new ReentrantLock(true));
```

`true` means fair mode. Threads acquire the lock roughly in arrival order.

### `ConcurrentHashMap`

The project uses `ConcurrentHashMap` when multiple threads may read and write a
map at the same time.

Examples:

- `ServerConnection.pending`
- `BidService.auctionLocks`
- `AuctionService.closeFutures`
- `AuctionEventBus.watchers`

A normal `HashMap` is unsafe for concurrent modification.

### `synchronized`

The project uses `synchronized` in some specific places.

Examples:

- DAO methods, to serialize access to the shared SQLite connection.
- `ClientHandler.send()`, to prevent two server threads from writing partial JSON
  lines to the same socket at the same time.

### `volatile`

`volatile` is used for fields read and written by different threads.

Examples:

- singleton instances in double-checked locking.
- `ServerConnection.broadcastListener`.
- some mutable fields in `Auction`.

`volatile` gives visibility. It does not make a sequence of actions atomic.

## 17. Auction Lifecycle and Scheduler

File:

```text
auction-server/src/main/java/com/auction/server/service/AuctionService.java
```

`AuctionService` manages:

- creating auctions.
- listing auctions.
- canceling auctions.
- scheduling auction close tasks.
- restoring schedules after server restart.
- anti-sniping.

### ScheduledExecutorService

The server uses a `ScheduledExecutorService` to run code later.

When an auction is created, the server schedules:

```text
closeAuction(auctionId) at auction.endTime
```

The scheduled future is stored in:

```java
ConcurrentHashMap<Long, ScheduledFuture<?>> closeFutures
```

This lets the server cancel and reschedule the close task if anti-sniping extends
the auction.

### Restore on Startup

If the server restarts, Java scheduled tasks are lost because they only lived in
memory.

So `AuctionService` constructor calls `restoreSchedules()`.

It queries the database for `OPEN` and `RUNNING` auctions and schedules close
tasks again.

That is why the database stores `end_time`. The schedule can be reconstructed.

### Anti-Sniping

Anti-sniping means preventing someone from winning by placing a bid at the last
second, leaving no time for others to respond.

Current rule:

- If a bid arrives within 30 seconds of auction end,
- extend the auction by 60 seconds.

Constants:

```java
ANTI_SNIPE_WINDOW_SECONDS = 30
ANTI_SNIPE_EXTENSION_SECONDS = 60
```

When anti-sniping happens:

1. `AuctionService` updates the in-memory `Auction`.
2. It updates `auctions.end_time` in SQLite.
3. It cancels the old scheduled close task.
4. It schedules a new close task.
5. It broadcasts `AUCTION_EXTENDED` to watchers.

## 18. Bidding and Auto-Bidding

File:

```text
auction-server/src/main/java/com/auction/server/service/BidService.java
```

This is the core business class.

### Manual Bid Flow

When a bidder places a bid:

1. Check the user can bid.
2. Acquire the per-auction lock.
3. Reload the auction from the database.
4. Validate auction status and price.
5. Reject if the bidder is already the leader.
6. Mark the auction as running if needed.
7. Persist the bid in `bid_transactions`.
8. Update `auctions.current_price` and `winner_id`.
9. Broadcast the bid to watchers.
10. Resolve auto-bids.
11. Apply anti-sniping if needed.
12. Release the lock.

### Auto-Bid Concept

An auto-bid has:

- `auctionId`
- `bidderId`
- `maxBid`
- `increment`
- `registeredAt`
- `active`

The bidder says:

```text
Keep bidding for me, by this increment, but never above this max.
```

### PriorityQueue

Auto-bids are resolved with a `PriorityQueue`.

The priority rule:

1. Higher `maxBid` wins.
2. If `maxBid` is equal, earlier `registeredAt` wins.

That means an auto-bid with max 500 beats one with max 400.
If both max values are 500, the one registered earlier wins.

The algorithm repeatedly picks the strongest eligible auto-bidder, creates a new
bid, updates price, broadcasts it, then checks again.

## 19. Authentication and Password Hashing

Files:

```text
auction-server/src/main/java/com/auction/server/service/UserService.java
auction-server/src/main/java/com/auction/server/util/PasswordUtil.java
```

### Registration

`UserService.register()` validates:

- username not blank.
- password length at least 4.
- email contains `@`.
- role is valid.
- role is not `ADMIN`.
- username is not already taken.

Then it:

1. Creates the correct `User` subclass.
2. Hashes the password.
3. Saves the user with `UserDAO`.

### Login

`UserService.login()`:

1. Finds user by username.
2. Rejects inactive users.
3. Verifies password.
4. Returns the domain `User`.

The server later maps that to `UserDTO` before sending it to the client.

### Password Hashing

The project stores password hashes, not plain passwords.

Modern format:

```text
BASE64(salt):HEX(SHA-256(salt + password))
```

Why salt matters:

- Without salt, two users with the same password have the same hash.
- With random salt, identical passwords produce different stored values.

The seeded admin account uses a legacy unsalted SHA-256 hash for password
`admin`. `PasswordUtil.verify()` supports both old and new formats.

Production note: SHA-256 with salt is better than plain text, but production
systems usually use BCrypt, SCrypt, Argon2, or PBKDF2 because they are deliberately
slower against brute-force attacks.

## 20. Error Handling

The server has custom exception types:

```text
auction-server/src/main/java/com/auction/server/exception/
  AuthException.java
  AuctionException.java
  BidException.java
```

Services throw these when business rules are violated.

`ClientHandler.dispatch()` catches them and sends:

```text
MessageType.ERROR
```

with an `ErrorResponse`.

The client checks:

```java
if (resp.getType() == MessageType.ERROR) {
    // show error text
}
```

This keeps business-rule failures separate from crashes or malformed messages.

## 21. Logging

The project uses SLF4J.

You will see:

```java
private static final Logger log = LoggerFactory.getLogger(SomeClass.class);
```

Benefits over `System.out.println()`:

- consistent format.
- log levels such as info, debug, warn, error.
- easy to replace logging backend later.

The project includes `slf4j-simple`, which is a simple logging implementation.

## 22. Testing

Tests are in:

```text
auction-server/src/test/java/com/auction/server/
```

The project uses:

- JUnit 5 for test structure and assertions.
- Mockito for mocks.
- SQLite temp files for integration tests.

### Unit Tests

Unit tests isolate one class and replace dependencies with mocks.

Example:

```text
UserServiceTest
```

It mocks `UserDAO`, so no database is needed.

This lets the test focus on rules such as:

- blank username rejected.
- short password rejected.
- duplicate username rejected.
- admin self-registration rejected.
- banned login rejected.

### Mockito

Mockito creates fake implementations of interfaces.

Example:

```java
UserDAO mockUserDAO = Mockito.mock(UserDAO.class);
```

Then tests define behavior:

```java
when(mockUserDAO.findByUsername("alice"))
    .thenReturn(Optional.of(existingUser));
```

And verify calls:

```java
verify(mockUserDAO).save(any());
```

### Integration Tests

Integration tests use real dependencies together.

Example:

```text
BidServiceTest
```

It uses real SQLite DAOs and a temporary database file.

That catches problems a mock cannot catch, such as:

- incorrect SQL.
- broken `ResultSet` mapping.
- wrong table relationships.
- persistence not matching later reads.

### Temporary Database

Tests set:

```java
System.setProperty("auction.db.url", "jdbc:sqlite:" + tempDb)
```

Then `DatabaseManager` connects to the temp database instead of `auction.db`.

After tests, `DatabaseManager.resetForTesting()` closes the connection.

This protects real project data from test data.

## 23. GitHub Actions CI

File:

```text
.github/workflows/ci.yml
```

CI means Continuous Integration. GitHub runs the workflow automatically on pushes
or pull requests.

The workflow:

1. Checks out the code.
2. Installs Java 17.
3. Builds `auction-common`.
4. Tests `auction-server`.
5. Compiles `auction-client`.
6. Uploads the server fat jar.
7. Publishes JUnit test results.

The client is compiled but not launched because JavaFX apps require a display,
which CI runners often do not have.

## 24. Main User Flows

### Login Flow

```text
LoginController
  -> Message LOGIN with LoginRequest
  -> ServerConnection.send()
  -> ClientHandler.handleLogin()
  -> UserService.login()
  -> UserDAO.findByUsername()
  -> PasswordUtil.verify()
  -> UserDTO response
  -> ClientSession.setCurrentUser()
  -> SceneManager switches by role
```

### Browse Auctions Flow

```text
AuctionListController
  -> GET_AUCTIONS
  -> ClientHandler.handleGetAuctions()
  -> AuctionService.getAllAuctions()
  -> AuctionDAO.findAll()
  -> DtoMapper.toDto()
  -> AUCTIONS_RESPONSE
  -> JavaFX TableView updates
```

### Create Auction Flow

```text
SellerDashboardController
  -> CREATE_ITEM
  -> ItemService.createItem()
  -> ItemFactory
  -> ItemDAO.save()

SellerDashboardController
  -> CREATE_AUCTION
  -> ItemService.getItem()
  -> AuctionService.createAuction()
  -> AuctionDAO.save()
  -> schedule close task
```

### Watch and Bid Flow

```text
AuctionDetailController.loadAuction(id)
  -> WATCH_AUCTION
  -> AuctionEventBus.subscribe()

Bidder clicks Place Bid
  -> PLACE_BID
  -> BidService.placeBid()
  -> per-auction lock
  -> BidDAO.save()
  -> AuctionDAO.updateCurrentPrice()
  -> AuctionEventBus.publishBidPlaced()
  -> ClientHandler.onBidPlaced()
  -> BID_BROADCAST
  -> AuctionDetailController.onBidBroadcast()
```

### Auction Close Flow

```text
AuctionService.scheduleClose()
  -> scheduled time arrives
  -> closeAuction()
  -> update winner/status
  -> AuctionEventBus.publishAuctionEnded()
  -> AUCTION_END_BROADCAST
  -> client UI shows final state
```

## 25. Important Design Patterns Used

### Singleton

Used by:

- `DatabaseManager`
- `AuctionEventBus`
- `ClientSession`

Purpose: one shared instance across the application.

Be careful: singletons are convenient, but they are global state. This can make
testing harder unless the class provides reset hooks, like `DatabaseManager`.

### DAO

Used by:

- `UserDAO`
- `ItemDAO`
- `AuctionDAO`
- `BidDAO`
- `AutoBidDAO`

Purpose: isolate SQL from business logic.

### Factory

Used by:

- `UserFactory`
- `ItemFactory`

Purpose: centralize creation of subclasses based on role/category.

### Observer

Used by:

- `AuctionObserver`
- `AuctionEventBus`
- `ClientHandler`

Purpose: notify watching clients when an auction changes.

### MVC-Like Client

FXML files are the view.
Controller classes handle UI events.
Server DTOs and client state provide the model-like data.

It is not a textbook MVC implementation, but the separation is similar.

## 26. Important Java APIs You May Need To Study

### `java.net`

Used for:

- `ServerSocket`
- `Socket`

Study topics:

- TCP connection lifecycle.
- blocking I/O.
- message framing with newlines.

### `java.io`

Used for:

- `BufferedReader`
- `InputStreamReader`
- `PrintWriter`
- `BufferedWriter`
- `OutputStreamWriter`

Study topics:

- streams.
- readers/writers.
- character encoding.
- `readLine()`.

### `java.sql`

Used for:

- `Connection`
- `PreparedStatement`
- `ResultSet`
- `Statement`

Study topics:

- SQL basics.
- parameterized queries.
- transactions.
- generated keys.
- handling nullable columns.

### `java.util.concurrent`

Used for:

- `ExecutorService`
- `ScheduledExecutorService`
- `ConcurrentHashMap`
- `CompletableFuture`
- `ScheduledFuture`
- `TimeUnit`
- `ReentrantLock`

Study topics:

- thread pools.
- locks.
- futures.
- scheduling.
- race conditions.
- thread-safe collections.

### JavaFX

Used for:

- `Application`
- `Stage`
- `Scene`
- `FXMLLoader`
- `TableView`
- `ObservableList`
- `LineChart`
- `Platform.runLater`

Study topics:

- JavaFX Application Thread.
- FXML injection.
- controller lifecycle.
- observable collections.
- cell value factories.

## 27. Where To Read First

Recommended reading order:

1. `README.md`
2. `auction-common/src/main/java/com/auction/common/protocol/Message.java`
3. `auction-common/src/main/java/com/auction/common/protocol/MessageType.java`
4. `auction-common/src/main/java/com/auction/common/request/Requests.java`
5. `auction-common/src/main/java/com/auction/common/request/Responses.java`
6. `auction-server/src/main/java/com/auction/server/network/AuctionServer.java`
7. `auction-server/src/main/java/com/auction/server/network/ClientHandler.java`
8. `auction-server/src/main/java/com/auction/server/service/UserService.java`
9. `auction-server/src/main/java/com/auction/server/service/AuctionService.java`
10. `auction-server/src/main/java/com/auction/server/service/BidService.java`
11. `auction-server/src/main/java/com/auction/server/db/DatabaseManager.java`
12. `auction-server/src/main/java/com/auction/server/dao/impl/SQLiteAuctionDAO.java`
13. `auction-client/src/main/java/com/auction/client/network/ServerConnection.java`
14. `auction-client/src/main/java/com/auction/client/util/SceneManager.java`
15. `auction-client/src/main/java/com/auction/client/controller/AuctionDetailController.java`
16. `auction-server/src/test/java/com/auction/server/service/BidServiceTest.java`

Read `BidService.java` and `AuctionDetailController.java` later because they
combine many concepts at once.

## 28. Things That May Surprise You

### The Client Has No Direct Database Access

All database work is on the server. The client only sends messages.

### The Server Can Send Messages Without a Request

`BID_BROADCAST`, `AUCTION_END_BROADCAST`, and `AUCTION_EXTENDED` are server-push
messages. They do not match an existing client future.

### `OPEN` Auctions Can Currently Be Bid Early

The code comments note that `BidService.validateBidState()` checks end time and
terminal statuses, but does not enforce `startTime`. If an auction is `OPEN`, a
valid bid can call `markRunning()` and move it to `RUNNING`.

That may be intentional for the assignment, but if strict start times are
required, `validateBidState()` should reject bids before `startTime`.

### `volatile` Does Not Replace Locks

`volatile` ensures visibility. It does not make this safe:

```text
read current price
compare bid
write new price
```

That sequence needs a lock.

### FXML Controllers Are Created By JavaFX

You usually do not call controller constructors manually. `FXMLLoader` creates
the controller and injects `@FXML` fields.

### Tests Can Use Either Mocks or Real SQLite

Mocks are faster and isolate business rules.
Real SQLite catches integration problems.

This project uses both approaches.

## 29. How To Add A New Feature

Example feature: add a "mark auction as paid" action.

Likely steps:

1. Add `MARK_PAID` and `AUCTION_PAID` to `MessageType`.
2. Add a request payload in `Requests.java`, probably with `auctionId`.
3. Add a response payload if `"OK"` is not enough.
4. Add a handler method in `ClientHandler`.
5. Add a service method in `AuctionService`.
6. Add or reuse a DAO update method.
7. Update the client screen with a button or action.
8. Send the message from the controller.
9. Handle success and error responses.
10. Add tests for permission and state rules.

That is the usual vertical slice:

```text
common protocol -> client UI -> server handler -> service -> DAO -> database -> tests
```

## 30. Practical Debugging Checklist

If login fails:

- Check server is running.
- Check client host and port.
- Check `users` table has the user.
- Check `PasswordUtil.verify()`.
- Check inactive/banned flag.

If a request gets no response:

- Check `ClientHandler.dispatch()` handles that `MessageType`.
- Check server logs for exceptions.
- Check client `pending` future is completed.
- Check request and response use the same `requestId`.

If live updates do not appear:

- Check client sent `WATCH_AUCTION`.
- Check `AuctionEventBus.subscribe()` was called.
- Check `BidService` publishes the event.
- Check `ClientHandler.onBidPlaced()` sends `BID_BROADCAST`.
- Check `ServerConnection.dispatchBroadcast()` sees a listener.
- Check UI updates happen through `Platform.runLater()`.

If database values look wrong:

- Check DAO SQL.
- Check `ResultSet` mapping.
- Check date parsing in `DateUtil`.
- Check nullable columns with `rs.wasNull()`.
- Check whether the service updates both in-memory object and database row.

If tests touch real data:

- Check `auction.db.url` system property.
- Check `DatabaseManager.resetForTesting()`.
- Check temp DB setup and teardown.

## 31. Summary

The project is not difficult mainly because of inheritance or interfaces. It is
difficult because many real application concerns interact:

- The client and server are separate processes.
- They communicate through a custom JSON-over-TCP protocol.
- The UI must stay responsive while network calls happen asynchronously.
- The server must handle multiple clients at the same time.
- Bids must be protected from race conditions.
- SQLite persistence must stay consistent with in-memory auction state.
- Live updates require an observer/event-bus design.
- Tests need both mocks and real database integration.

If you understand the path from a button click in JavaFX to a `Message`, then to
`ClientHandler`, then to a service, then to a DAO, then back as a DTO response,
you understand the core structure of the whole project.
