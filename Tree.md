│   .gitattributes
│   .gitignore
│   auction.db
│   pom.xml
│   README.md
│
├───.github
│   └───workflows
│           ci.yml
│
├───auction-client
│   │   dependency-reduced-pom.xml
│   │   pom.xml
│   │
│   ├───src
│   │   └───main
│   │       ├───java
│   │       │   └───com
│   │       │       └───auction
│   │       │           └───client
│   │       │               │   ClientMain.java
│   │       │               │
│   │       │               ├───controller
│   │       │               │       AdminController.java
│   │       │               │       AuctionDetailController.java
│   │       │               │       AuctionListController.java
│   │       │               │       LoginController.java
│   │       │               │       RegisterController.java
│   │       │               │       SellerDashboardController.java
│   │       │               │
│   │       │               ├───network
│   │       │               │       ServerConnection.java
│   │       │               │
│   │       │               ├───session
│   │       │               │       ClientSession.java
│   │       │               │
│   │       │               └───util
│   │       │                       AlertUtil.java
│   │       │                       SceneManager.java
│   │       │
│   │       └───resources
│   │           │   simplelogger.properties
│   │           │
│   │           └───com
│   │               └───auction
│   │                   └───client
│   │                       ├───css
│   │                       │       style.css
│   │                       │
│   │                       └───fxml
│   │                               admin_panel.fxml
│   │                               auction_detail.fxml
│   │                               auction_list.fxml
│   │                               login.fxml
│   │                               register.fxml
│                                   seller_dashboard.fxml
|   
├───auction-common
│   │   pom.xml
│   │
│   ├───src
│   │   └───main
│   │       └───java
│   │           └───com
│   │               └───auction
│   │                   └───common
│   │                       ├───dto
│   │                       │       AuctionDTO.java
│   │                       │       BidDTO.java
│   │                       │       ItemDTO.java
│   │                       │       package-info.java
│   │                       │       UserDTO.java
│   │                       │
│   │                       ├───protocol
│   │                       │       Message.java
│   │                       │       MessageType.java
│   │                       │
│   │                       └───request
│   │                               EmptyPayload.java
│   │                               Requests.java
│                                   Responses.java
│   
├───auction-server
│   │   dependency-reduced-pom.xml
│   │   pom.xml
│   │
│   ├───src
│   │   ├───main
│   │   │   ├───java
│   │   │   │   └───com
│   │   │   │       └───auction
│   │   │   │           └───server
│   │   │   │               │   ServerMain.java
│   │   │   │               │
│   │   │   │               ├───dao
│   │   │   │               │   │   AuctionDAO.java
│   │   │   │               │   │   AutoBidDAO.java
│   │   │   │               │   │   BidDAO.java
│   │   │   │               │   │   ItemDAO.java
│   │   │   │               │   │   UserDAO.java
│   │   │   │               │   │
│   │   │   │               │   └───impl
│   │   │   │               │           SQLiteAuctionDAO.java
│   │   │   │               │           SQLiteAutoBidDAO.java
│   │   │   │               │           SQLiteBidDAO.java
│   │   │   │               │           SQLiteItemDAO.java
│   │   │   │               │           SQLiteUserDAO.java
│   │   │   │               │
│   │   │   │               ├───db
│   │   │   │               │       DatabaseManager.java
│   │   │   │               │
│   │   │   │               ├───exception
│   │   │   │               │       AuctionException.java
│   │   │   │               │       AuthException.java
│   │   │   │               │       BidException.java
│   │   │   │               │
│   │   │   │               ├───factory
│   │   │   │               │       ItemFactory.java
│   │   │   │               │       UserFactory.java
│   │   │   │               │
│   │   │   │               ├───model
│   │   │   │               │       Admin.java
│   │   │   │               │       Art.java
│   │   │   │               │       Auction.java
│   │   │   │               │       AuctionStatus.java
│   │   │   │               │       AutoBid.java
│   │   │   │               │       Bidder.java
│   │   │   │               │       BidTransaction.java
│   │   │   │               │       Electronics.java
│   │   │   │               │       Entity.java
│   │   │   │               │       Item.java
│   │   │   │               │       ItemCategory.java
│   │   │   │               │       Seller.java
│   │   │   │               │       User.java
│   │   │   │               │       UserRole.java
│   │   │   │               │       Vehicle.java
│   │   │   │               │
│   │   │   │               ├───network
│   │   │   │               │       AuctionServer.java
│   │   │   │               │       ClientHandler.java
│   │   │   │               │
│   │   │   │               ├───observer
│   │   │   │               │       AuctionEventBus.java
│   │   │   │               │       AuctionObserver.java
│   │   │   │               │
│   │   │   │               ├───service
│   │   │   │               │       AuctionService.java
│   │   │   │               │       BidService.java
│   │   │   │               │       ItemService.java
│   │   │   │               │       UserService.java
│   │   │   │               │
│   │   │   │               └───util
│   │   │   │                       DateUtil.java
│   │   │   │                       DtoMapper.java
│   │   │   │                       PasswordUtil.java
│   │   │   │
│   │   │   └───resources
│   │   │           simplelogger.properties
│   │   │
│   │   └───test
│   │       └───java
│   │           └───com
│   │               └───auction
│   │                   └───server
│   │                       ├───service
│   │                       │       AuctionServiceTest.java
│   │                       │       BidServiceTest.java
│   │                       │       UserServiceTest.java
│   │                       │
│   │                       └───util
│                                   PasswordUtilTest.java
