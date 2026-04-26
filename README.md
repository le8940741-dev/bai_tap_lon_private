# Online Auction System

A full-stack Java desktop application: real-time online auction platform built with
**JavaFX 21**, **SQLite JDBC**, **TCP sockets**, and a strict layered architecture.

---

## Design Rationale

### Why TCP Sockets (not REST)?

REST is request-response only. The core requirement of this system is that
**all watching clients see a new bid immediately** without polling. REST would
require each client to poll every second — wasteful and laggy. A persistent TCP
connection lets the server *push* bid events the instant they are accepted.
The trade-off: more complex client code (CompletableFuture correlation + broadcast
routing) versus simpler HTTP verbs. The payoff is sub-millisecond latency for
bid broadcasts.

### Why SQLite (not a full RDBMS)?

SQLite is zero-configuration: no server process, no install, one file. For a
desktop application with one server process, it is perfectly appropriate.
The concurrency ceiling (one writer at a time) is handled by `synchronized` DAO
methods. The `DatabaseManager` exposes a `resetForTesting()` hook so tests can
point at a temp file without touching production data.

### Why Manual Dependency Injection (not Spring)?

The dependency graph is shallow (DAO → Service → Handler, 3 layers). A DI
framework would add classpath scanning, annotation processing, and startup time
for no meaningful benefit. Manual wiring in `AuctionServer`'s constructor makes
every dependency explicit and traceable in one place.

### Concurrency: Why Per-Auction `ReentrantLock`?

A global `synchronized` block would serialise ALL bids across ALL auctions.
A per-auction `ReentrantLock` stored in a `ConcurrentHashMap` means:
- Bidding on auction #1 never blocks bidding on auction #2.
- Within one auction, bids are strictly serialised (no lost-update, no two winners).
- The `fair=true` flag prevents bid starvation: fast-retrying clients don't skip slow ones.

### Why `volatile` on `Auction` fields?

`volatile` guarantees that a write by thread A (BidService, holding the lock)
is immediately visible to thread B (the scheduler thread, not holding the lock).
Without it, the scheduler might read a stale CPU-cached `endTime` and close the
auction at the wrong time. `volatile` does not make compound operations atomic —
that still requires the `ReentrantLock`.

### Why `CompletableFuture` on the client?

Blocking the JavaFX Application Thread waiting for a network response freezes
the UI (no repaints, no button clicks). `CompletableFuture.whenCompleteAsync`
fires a callback when the response arrives, keeping the FX thread free.
`Platform.runLater()` inside the callback schedules the actual UI update back
on the FX thread, which is the only thread allowed to touch UI components.

### Design Pattern Choices

| Pattern | Where | Why chosen |
|---|---|---|
| **Singleton** | `DatabaseManager`, `AuctionEventBus`, `ClientSession` | One shared instance needed across many independently-created objects; avoids passing through constructors |
| **Factory Method** | `ItemFactory`, `UserFactory` | Centralises subclass instantiation; adding a new category/role requires one change here, zero changes elsewhere |
| **Observer** | `AuctionObserver` ← `ClientHandler`; `AuctionEventBus` fan-out | Decouples `BidService` from the network layer; BidService doesn't know how many clients are watching or how to write to a socket |
| **DAO** | All five `SQLite*DAO` classes | Isolates SQL from business logic; services test with mock DAOs, no database needed |
| **Strategy** (implicit) | `BidService.resolveAutoBids()` PriorityQueue comparator | The tie-breaking rule (maxBid DESC, registeredAt ASC) is a pluggable comparison strategy |
| **MVC** | Client (FXML + Controller) and Server (Handler → Service → DAO) | Separates display (FXML), user-action handling (Controller), and business logic (Service) |

---

## Reading Order

Read the files in this order to understand the system from foundations to features.

### Step 1 — The Protocol (what travels over the wire)

Start here to understand the shared language between client and server.
All communication is newline-delimited JSON; every message is one `Message` object.

```
auction-common/
  protocol/
    MessageType.java   ← every possible action/event (the "verb" of the protocol)
    Message.java       ← the envelope (requestId + type + payload)
  request/
    EmptyPayload.java  ← why this exists (Gson null-payload bug)
    Requests.java      ← all client→server payload classes (one per MessageType)
    Responses.java     ← all server→client payload classes
  dto/
    UserDTO.java       ← user without passwordHash
    ItemDTO.java       ← item with category + extraData
    AuctionDTO.java    ← auction with embedded ItemDTO + current price/leader
    BidDTO.java        ← single bid with dual timestamp (display + chart)
```

### Step 2 — The Domain Model (what exists in the server's world)

These are pure Java objects with no SQL and no network code.
Read them to understand what the system is modelling.

```
auction-server/model/
  Entity.java         ← abstract root: id + createdAt + printInfo()
  UserRole.java       ← BIDDER / SELLER / ADMIN
  User.java           ← abstract: canBid(), canSell(), getRole()
  Bidder.java         ← canBid()=true, canSell()=false
  Seller.java         ← canBid()=false, canSell()=true
  Admin.java          ← canBid()=false, canSell()=false
  ItemCategory.java   ← ELECTRONICS / ART / VEHICLE
  Item.java           ← abstract: getCategory(), extraData blob
  Electronics.java    ← getCategory()=ELECTRONICS
  Art.java            ← getCategory()=ART
  Vehicle.java        ← getCategory()=VEHICLE
  AuctionStatus.java  ← OPEN → RUNNING → FINISHED → PAID / CANCELED
  Auction.java        ← volatile fields; isActive(); embedded Item
  BidTransaction.java ← append-only bid record; autoBid flag
  AutoBid.java        ← maxBid + increment + registeredAt (tie-breaker)
```

### Step 3 — Infrastructure (database and utilities)

```
auction-server/db/
  DatabaseManager.java   ← Singleton; SQLite connection; schema init; admin seed

auction-server/util/
  DateUtil.java          ← why LocalDateTime.parse() alone fails on SQLite dates
  PasswordUtil.java      ← SHA-256 + random salt; legacy hash support for admin seed
  DtoMapper.java         ← domain object → DTO (the translation layer)

auction-server/exception/
  AuthException.java     ← login/registration/ban violations
  BidException.java      ← bidding rule violations
  AuctionException.java  ← auction lifecycle violations
```

### Step 4 — Persistence (DAOs)

Read the interface first, then the implementation.
The interface is the contract; the implementation is how SQLite fulfils it.

```
auction-server/dao/
  UserDAO.java        ← interface: save, findById, findByUsername, findAll, updateActive
  ItemDAO.java        ← interface: save, findById, findBySellerId
  AuctionDAO.java     ← interface: 9 methods including fine-grained updates
  BidDAO.java         ← interface: save, findByAuctionId
  AutoBidDAO.java     ← interface: save (UPSERT), findActiveByAuctionId, deactivate

  impl/SQLiteUserDAO.java     ← synchronized; PreparedStatement; UserFactory.create()
  impl/SQLiteItemDAO.java     ← JOIN for seller_name; ItemFactory.create()
  impl/SQLiteAuctionDAO.java  ← 3-table JOIN; rs.wasNull() for nullable winner
  impl/SQLiteBidDAO.java      ← append-only; ASC order for chart
  impl/SQLiteAutoBidDAO.java  ← ON CONFLICT DO UPDATE (UPSERT)
```

### Step 5 — Factories and Observer (design patterns)

```
auction-server/factory/
  ItemFactory.java    ← switch expression on ItemCategory → correct subclass
  UserFactory.java    ← switch expression on UserRole → correct subclass

auction-server/observer/
  AuctionObserver.java   ← interface: onBidPlaced, onAuctionEnded, onAuctionExtended
  AuctionEventBus.java   ← Singleton; ConcurrentHashMap of watcher sets; async fan-out
```

### Step 6 — Business Logic (services)

This is where the rules of the auction system live.

```
auction-server/service/
  UserService.java     ← register (validation + hashing), login, banUser
  ItemService.java     ← createItem (seller-only, factory), getItem, getItemsBySeller
  AuctionService.java  ← createAuction, scheduler (ScheduledFuture), anti-snipe, closeAuction
  BidService.java      ← placeBid (ReentrantLock), resolveAutoBids (PriorityQueue), setAutoBid
```

Read `BidService.java` last in this group — it is the most complex class and
builds on all the others.

### Step 7 — Network Layer (server side)

```
auction-server/network/
  AuctionServer.java   ← wires all DAOs+services; accept loop; thread pool
  ClientHandler.java   ← one per client; dispatch table; implements AuctionObserver
  ServerMain.java      ← entry point: parse port, init DB, start server
```

`ClientHandler` is the second most complex class. It is the bridge between the
network (TCP sockets) and the business layer (services). Read the dispatch()
method and then follow each handleXxx() method.

### Step 8 — Client Network Layer

```
auction-client/network/
  ServerConnection.java  ← CompletableFuture correlation + broadcast routing
```

Read the `route()` method to understand the two-path dispatch.
Read `send()` + `readLoop()` to understand the async communication model.

### Step 9 — Client Session and Navigation

```
auction-client/session/
  ClientSession.java   ← Singleton: connection + logged-in UserDTO + role helpers

auction-client/util/
  SceneManager.java    ← FXML cache; switchTo(); showAuctionDetail(); Refreshable
  AlertUtil.java       ← error(), info(), confirm() wrappers
```

### Step 10 — Client Entry Point and Controllers

```
auction-client/
  ClientMain.java                           ← connect → store in session → show LOGIN

  controller/LoginController.java           ← async LOGIN → navigate by role
  controller/RegisterController.java        ← REGISTER → back to login
  controller/AuctionListController.java     ← GET_AUCTIONS; FilteredList search
  controller/AuctionDetailController.java   ← most complex: bidding + chart + timer + broadcasts
  controller/SellerDashboardController.java ← create items/auctions; cancel; refresh
  controller/AdminController.java           ← user list; ban user
```

Read `AuctionDetailController` last — it combines everything: network calls,
broadcast handling, chart updates, and a countdown timer.

### Step 11 — Tests

```
auction-server/test/
  util/PasswordUtilTest.java      ← hash/verify round-trip; legacy hash format
  service/UserServiceTest.java    ← Mockito mock DAO; validates registration rules
  service/AuctionServiceTest.java ← Mockito mock DAO; anti-snipe window logic
  service/BidServiceTest.java     ← real SQLite temp file; full bid + auto-bid flow
```

Read `BidServiceTest` last — it is an integration test (real DB) and exercises
the most code paths of any test in the project.

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────┐
│                    CLIENT PROCESS                        │
│                                                          │
│  JavaFX FX Thread          server-reader Thread          │
│  ┌────────────────┐        ┌──────────────────────────┐  │
│  │ Controller     │        │  ServerConnection         │  │
│  │ (sends msg)    │──────▶ │  readLoop()               │  │
│  │                │        │  ├─ route(msg)             │  │
│  │ whenComplete   │◀──────-│  │  ├─ future.complete()  │  │
│  │ (FX callback)  │        │  │  └─ dispatchBroadcast()│  │
│  └────────────────┘        │  │     └─ Platform.runLater│  │
│                            └──────────────────────────┘  │
└───────────────────────────┬─────────────────────────────┘
                            │ TCP (newline-delimited JSON)
┌───────────────────────────▼─────────────────────────────┐
│                    SERVER PROCESS                        │
│                                                          │
│  Per-client thread         notify-pool threads           │
│  ┌────────────────┐        ┌──────────────────────────┐  │
│  │ ClientHandler  │        │  AuctionEventBus          │  │
│  │ dispatch()     │        │  fan() → observer.onXxx() │  │
│  │ ├─ UserService │        └──────────────────────────┘  │
│  │ ├─ ItemService │                    ▲                 │
│  │ ├─ AuctionSvc  │──────publishes─────┘                 │
│  │ └─ BidService  │                                      │
│  │   (acquires    │                                      │
│  │    auction     │     scheduler Thread                 │
│  │    lock)       │   ┌────────────────────┐             │
│  └────────────────┘   │ closeAuction()     │             │
│                       │ (fires at endTime) │             │
│                       └────────────────────┘             │
│                                                          │
│  ┌───────────────────────────────────────────────┐       │
│  │              SQLite (auction.db)              │       │
│  │  users  items  auctions  bid_transactions     │       │
│  │  auto_bids                                    │       │
│  └───────────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────────┘
```

---

## Build and Run

```bash
# Build and test
mvn install

# Start server (creates auction.db in current directory)
java -jar auction-server/target/auction-server-1.0.0-fat.jar

# Start client
mvn -pl auction-client javafx:run

# Login: admin / admin

# Run tests only (no display needed)
mvn -pl auction-common,auction-server test
```

---

## Default Admin Account

| Field    | Value   |
|----------|---------|
| Username | `admin` |
| Password | `admin` |

The hash stored in the database is SHA-256("admin") with no salt prefix.
`PasswordUtil.verify()` detects the absence of a ":" separator and falls back
to a bare SHA-256 comparison for this legacy format.
