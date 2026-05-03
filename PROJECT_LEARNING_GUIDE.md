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

## 5. `auction-common`: The Shared Protocol

The common module is the shared language between client and server.

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

## 11. SQLite and JDBC

SQLite is a file-based database. In this project, the database file is:

```text
auction.db
```

The server uses JDBC to talk to SQLite.

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
