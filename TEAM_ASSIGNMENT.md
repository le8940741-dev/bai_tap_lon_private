# Team Assignment

This assignment is based on the project structure described in `PROJECT_LEARNING_GUIDE.md`.
The project is split into three main technical areas:

- `auction-client`: JavaFX frontend.
- `auction-server`: backend server, business rules, database access, and TCP networking.
- `auction-common`: shared DTOs, request/response payloads, and protocol types used by both client and server.

## Recommended Role Split

| Person | Main Role | Main Ownership |
| --- | --- | --- |
| Student 1 | Database and frontend-backend integration | SQLite, JDBC, DAO layer, shared protocol, client-server connection, integration testing |
| Student 2 | Frontend A | Login, registration, navigation, general auction browsing UI |
| Student 3 | Frontend B | Seller, bidder, auction detail, bidding, and admin UI |
| Student 4 | Backend | Server business logic, socket request handling, domain models, services, concurrency, auction lifecycle |

## Student 1: Database and Integration

Student 1 is responsible for the parts that connect the whole system together. Your role is not only "database"; it is also making sure the frontend and backend can communicate correctly.

### Primary Files and Folders

- `auction-server/src/main/java/com/auction/server/db/`
- `auction-server/src/main/java/com/auction/server/dao/`
- `auction-server/src/main/java/com/auction/server/dao/impl/`
- `auction-server/src/main/java/com/auction/server/util/DtoMapper.java`
- `auction-common/src/main/java/com/auction/common/dto/`
- `auction-common/src/main/java/com/auction/common/protocol/`
- `auction-common/src/main/java/com/auction/common/request/`
- `auction-client/src/main/java/com/auction/client/network/ServerConnection.java`
- `auction.db`

### Exact Responsibilities

- Design and maintain the SQLite database schema.
- Maintain `DatabaseManager`, including table creation and default data such as the admin account.
- Implement and fix DAO interfaces and SQLite DAO classes:
  - `UserDAO` / `SQLiteUserDAO`
  - `ItemDAO` / `SQLiteItemDAO`
  - `AuctionDAO` / `SQLiteAuctionDAO`
  - `BidDAO` / `SQLiteBidDAO`
  - `AutoBidDAO` / `SQLiteAutoBidDAO`
- Make sure DAO methods are correct, synchronized where needed, and use prepared statements.
- Maintain the shared network contract in `auction-common`:
  - `Message`
  - `MessageType`
  - DTO classes
  - request classes
  - response classes
- Coordinate request/response payload changes between frontend and backend.
- Maintain DTO mapping from server models to safe client-facing data in `DtoMapper`.
- Maintain the client network wrapper `ServerConnection`.
- Verify that the client never accesses the database directly.
- Create integration checks for complete flows:
  - register
  - login
  - browse auctions
  - create item
  - create auction
  - place bid
  - receive bid update
  - close auction

### Deliverables

- Working database initialization.
- Correct DAO implementations.
- Stable request/response protocol.
- Working client-server connection.
- Integration proof that frontend screens can call backend features successfully.
- Short explanation of how data moves from JavaFX to server to SQLite and back.

## Student 2: Frontend A

Student 2 owns the entry and browsing experience. This person should focus on making the JavaFX application usable before the user enters complex seller or bidder workflows.

### Primary Files and Folders

- `auction-client/src/main/java/com/auction/client/controller/LoginController.java`
- `auction-client/src/main/java/com/auction/client/controller/RegisterController.java`
- `auction-client/src/main/java/com/auction/client/controller/AuctionListController.java`
- `auction-client/src/main/java/com/auction/client/session/ClientSession.java`
- `auction-client/src/main/java/com/auction/client/util/SceneManager.java`
- `auction-client/src/main/java/com/auction/client/util/AlertUtil.java`
- `auction-client/src/main/resources/com/auction/client/fxml/login.fxml`
- `auction-client/src/main/resources/com/auction/client/fxml/register.fxml`
- `auction-client/src/main/resources/com/auction/client/fxml/auction_list.fxml`
- `auction-client/src/main/resources/com/auction/client/css/style.css`

### Exact Responsibilities

- Build and polish the login screen.
- Build and polish the registration screen.
- Handle validation for user input before sending requests:
  - empty username
  - empty password
  - invalid role
  - mismatched or weak fields if the UI supports them
- Send login and registration requests through `ServerConnection`.
- Store the current user in `ClientSession` after successful login.
- Redirect users to the correct screen based on role:
  - bidder
  - seller
  - admin
- Build the auction browsing screen.
- Display auction list data using JavaFX controls such as `TableView` or lists.
- Show clear error messages using `AlertUtil`.
- Work with Student 1 when request/response fields are missing or unclear.
- Work with Student 3 so navigation into auction detail screens is consistent.

### Deliverables

- Login UI.
- Registration UI.
- Auction list UI.
- Role-based navigation.
- Clean user-facing validation and error handling.
- Confirmation that users can log in, register, and browse auctions from the client.

## Student 3: Frontend B

Student 3 owns the deeper application workflows after login. This includes seller tools, bidder tools, auction detail, bidding, and admin screens.

### Primary Files and Folders

- `auction-client/src/main/java/com/auction/client/controller/SellerDashboardController.java`
- `auction-client/src/main/java/com/auction/client/controller/AuctionDetailController.java`
- `auction-client/src/main/java/com/auction/client/controller/AdminController.java`
- `auction-client/src/main/resources/com/auction/client/fxml/seller_dashboard.fxml`
- `auction-client/src/main/resources/com/auction/client/fxml/auction_detail.fxml`
- `auction-client/src/main/resources/com/auction/client/fxml/admin_panel.fxml`
- `auction-client/src/main/resources/com/auction/client/css/style.css`

### Exact Responsibilities

- Build the seller dashboard.
- Let sellers create items.
- Let sellers create auctions for their items.
- Build the auction detail screen.
- Display full auction information:
  - item name
  - category
  - description
  - seller
  - current price
  - status
  - end time
  - current winner if allowed
- Implement bidder actions in the UI:
  - watch auction
  - place bid
  - set auto-bid if supported by the backend
- Update the auction detail screen when bid broadcasts arrive from the server.
- Build the admin screen.
- Let admins view users and ban accounts if the backend supports it.
- Keep JavaFX UI updates on the JavaFX Application Thread.
- Work with Student 1 on real-time update messages.
- Work with Student 4 to understand backend validation errors and show them clearly.

### Deliverables

- Seller dashboard UI.
- Auction detail UI.
- Bidding UI.
- Live bid update handling.
- Admin panel UI.
- Confirmation that seller, bidder, and admin workflows can be demonstrated from the client.

## Student 4: Backend

Student 4 owns the trusted server-side behavior. This person should focus on business correctness, not UI.

### Primary Files and Folders

- `auction-server/src/main/java/com/auction/server/ServerMain.java`
- `auction-server/src/main/java/com/auction/server/network/`
- `auction-server/src/main/java/com/auction/server/service/`
- `auction-server/src/main/java/com/auction/server/model/`
- `auction-server/src/main/java/com/auction/server/factory/`
- `auction-server/src/main/java/com/auction/server/observer/`
- `auction-server/src/main/java/com/auction/server/exception/`
- `auction-server/src/main/java/com/auction/server/util/PasswordUtil.java`
- `auction-server/src/main/java/com/auction/server/util/DateUtil.java`
- `auction-server/src/test/java/com/auction/server/`

### Exact Responsibilities

- Maintain the TCP server startup flow.
- Maintain `AuctionServer` and `ClientHandler`.
- Parse incoming messages and route each `MessageType` to the correct service method.
- Implement authentication behavior:
  - register
  - login
  - password hashing
  - role checks
  - account ban checks
- Implement user, item, auction, and bid business rules in services:
  - `UserService`
  - `ItemService`
  - `AuctionService`
  - `BidService`
- Maintain server domain models:
  - `User`
  - `Seller`
  - `Bidder`
  - `Admin`
  - `Item`
  - `Auction`
  - `BidTransaction`
  - `AutoBid`
- Maintain factories:
  - `UserFactory`
  - `ItemFactory`
- Implement bidding correctness:
  - bid must be higher than current price
  - banned users cannot bid
  - seller cannot bid on own auction if that rule is required
  - leading bidder cannot immediately outbid themselves if that rule is required
  - concurrent bids are handled safely
- Maintain auction lifecycle:
  - auction start and end time
  - scheduled auction closing
  - winner selection
  - status changes
  - anti-sniping extension if supported
- Maintain real-time broadcasts through `AuctionEventBus` and `AuctionObserver`.
- Write and maintain backend tests.

### Deliverables

- Running TCP server.
- Correct business rules.
- Working service layer.
- Working request handling in `ClientHandler`.
- Working auction close scheduler.
- Working bid broadcast behavior.
- Unit tests for important backend rules.

## Shared Ownership Boundaries

Some files affect more than one role. These should not be changed without communication.

| Area | Owner | Must Coordinate With |
| --- | --- | --- |
| `auction-common` DTOs and requests | Student 1 | Students 2, 3, and 4 |
| `MessageType` | Student 1 | Students 2, 3, and 4 |
| `ClientHandler` request routing | Student 4 | Student 1 |
| `ServerConnection` | Student 1 | Students 2 and 3 |
| `style.css` | Students 2 and 3 | Each other |
| Database schema | Student 1 | Student 4 |
| Service method signatures | Student 4 | Student 1 |
| User-facing validation messages | Students 2 and 3 | Student 4 |

## Feature-by-Feature Division

| Feature | Frontend Owner | Backend Owner | Database/Integration Owner |
| --- | --- | --- | --- |
| Register | Student 2 | Student 4 | Student 1 |
| Login | Student 2 | Student 4 | Student 1 |
| Browse auctions | Student 2 | Student 4 | Student 1 |
| Create item | Student 3 | Student 4 | Student 1 |
| Create auction | Student 3 | Student 4 | Student 1 |
| Watch auction | Student 3 | Student 4 | Student 1 |
| Place bid | Student 3 | Student 4 | Student 1 |
| Auto-bid | Student 3 | Student 4 | Student 1 |
| Auction close | Student 3 | Student 4 | Student 1 |
| Admin ban user | Student 3 | Student 4 | Student 1 |

## Handoff Rules

Use these rules to avoid confusion:

1. Backend features should be implemented service-first.
2. Student 4 defines what the server can do.
3. Student 1 exposes that behavior through `auction-common`, `ClientHandler`, DAO methods, and `ServerConnection`.
4. Students 2 and 3 call `ServerConnection` from controllers instead of touching sockets, JSON, or the database directly.
5. Any new feature must define:
   - `MessageType`
   - request payload
   - response payload
   - backend service method
   - DAO/database change if needed
   - frontend controller action
   - success and error behavior

## Suggested Implementation Order

1. Student 1 verifies the database schema, DAO layer, `auction-common`, and `ServerConnection`.
2. Student 4 verifies backend services and `ClientHandler` request handling.
3. Student 2 finishes login, register, navigation, and auction list.
4. Student 3 finishes seller dashboard, auction detail, bidding, live updates, and admin panel.
5. All members test the main flows together.

## Demo Responsibility

For the final demo, each person should explain the part they own:

- Student 1: Explain database tables, DAOs, DTOs, request/response messages, and how client-server communication works.
- Student 2: Demonstrate login, registration, role navigation, and auction browsing.
- Student 3: Demonstrate seller dashboard, auction detail, bidding, live updates, and admin panel.
- Student 4: Explain services, business rules, TCP server handling, concurrency, auction closing, and tests.

## Main Test Checklist

- Build the full Maven project.
- Start `auction-server`.
- Start `auction-client`.
- Register a bidder.
- Register a seller.
- Log in as seller.
- Create an item.
- Create an auction.
- Log in as bidder.
- Browse auctions.
- Watch an auction.
- Place a valid bid.
- Try an invalid bid and confirm the server rejects it.
- Confirm the UI shows the new bid.
- Confirm auction closing behavior works.
- Log in as admin.
- View users and test ban behavior if included in the final scope.

