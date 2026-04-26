package com.auction.server.service;

// DAO implementations — the real SQLite ones, not mocks.
// This is an integration test: we use an actual (temp-file) database.
import com.auction.server.dao.impl.*;

// The system property mechanism that lets us point DatabaseManager at a temp file:
import com.auction.server.db.DatabaseManager;

// The exception we expect on invalid bids:
import com.auction.server.exception.BidException;

// Domain models needed to set up test data:
import com.auction.server.model.*;

// JUnit 5 lifecycle and assertion imports:
import org.junit.jupiter.api.*;            // @BeforeAll, @AfterAll, @Test, @Order, MethodOrderer
import static org.junit.jupiter.api.Assertions.*;

// Java I/O for temp file management:
import java.io.IOException;
import java.nio.file.Files; // Files.createTempFile(), Files.deleteIfExists()
import java.nio.file.Path;  // represents the temp DB file path

// Time for setting up auction windows:
import java.time.LocalDateTime;

/**
 * FILE ROLE: Integration tests for BidService — the most critical service.
 *
 * INTEGRATION TEST (vs. UNIT TEST):
 *   Unlike UserServiceTest which uses a mock DAO, this test uses REAL DAO
 *   implementations backed by a real SQLite database in a temp file.
 *
 *   WHY: BidService's correctness depends on the DAO reading back the exact
 *   state it wrote — a mock DAO can't catch bugs in SQL queries or ResultSet
 *   mapping.  The full stack (BidService → SQLiteBidDAO → SQLite → read back)
 *   must work end-to-end for the bid history and auto-bid resolution to be correct.
 *
 * ISOLATION VIA TEMP FILE:
 *   Each test class gets its own SQLite temp file, created in @BeforeAll.
 *   DatabaseManager is reset before creating the file (resetForTesting()) and
 *   after the tests finish (@AfterAll).  This prevents test state from leaking
 *   into other test classes or into the production "auction.db" file.
 *
 * WINDOWS FILE LOCK HANDLING:
 *   SQLite on Windows holds an OS-level file lock for a brief moment after
 *   connection.close().  The teardown uses a retry loop with 100ms sleeps
 *   to wait for the lock to release before deleting the temp file.
 *   System.gc() hints to the JVM to finalize any lingering references sooner.
 *
 * TEST ORDER:
 *   Tests are ordered with @Order because they share a single auction.
 *   Each test leaves the auction in a specific state that the next test
 *   depends on (e.g. test 1 places the first bid; test 2 tries to outbid it).
 *   @TestMethodOrder(MethodOrderer.OrderAnnotation.class) enforces this order.
 *
 * WHAT WE COVER:
 *   - Manual bid accepted and price updated in DB.
 *   - Bid below current price rejected (BidException).
 *   - Leading bidder cannot bid again (BidException).
 *   - A second bidder can outbid and become the new leader.
 *   - setAutoBid() fires immediately when maxBid > currentPrice.
 *   - getBidHistory() returns all bids in ascending chronological order.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class) // enforce @Order on test methods
class BidServiceTest {

    // Path to the temp SQLite file — set in @BeforeAll, deleted in @AfterAll.
    static Path tempDb;

    // Real DAO instances — all share the same DatabaseManager connection.
    static SQLiteUserDAO    userDAO;
    static SQLiteItemDAO    itemDAO;
    static SQLiteAuctionDAO auctionDAO;
    static SQLiteBidDAO     bidDAO;
    static SQLiteAutoBidDAO autoBidDAO;

    // Services under test — AuctionService is needed because BidService
    // calls auctionService.markRunning() and auctionService.applyAntiSnipe().
    static AuctionService auctionService;
    static BidService     bidService;

    // Test fixtures — created once and reused across all test methods.
    static Bidder  bidder1;  // will be the initial bidder
    static Bidder  bidder2;  // will outbid bidder1
    static Auction auction;  // the auction all tests bid on

    @BeforeAll
    static void setup() throws IOException {
        // 1. Create a temp SQLite file in the OS temp directory.
        tempDb = Files.createTempFile("auction_test_", ".db");

        // 2. Tell DatabaseManager to use this file instead of "auction.db".
        System.setProperty("auction.db.url", "jdbc:sqlite:" + tempDb.toAbsolutePath());

        // 3. Destroy any existing singleton (from a previous test class or production use).
        DatabaseManager.resetForTesting();

        // 4. Create a fresh DatabaseManager — this runs initSchema() on the temp file.
        DatabaseManager.getInstance();

        // 5. Instantiate the real DAOs (they all call DatabaseManager.getInstance().getConnection()).
        userDAO    = new SQLiteUserDAO();
        itemDAO    = new SQLiteItemDAO();
        auctionDAO = new SQLiteAuctionDAO();
        bidDAO     = new SQLiteBidDAO();
        autoBidDAO = new SQLiteAutoBidDAO();

        // 6. Instantiate services.
        auctionService = new AuctionService(auctionDAO);
        bidService     = new BidService(auctionDAO, bidDAO, autoBidDAO, auctionService);

        // 7. Persist test users into the temp database.
        bidder1 = new Bidder();
        bidder1.setUsername("alice_test");
        bidder1.setPasswordHash("hash");
        bidder1.setEmail("alice_test@test.local");
        userDAO.save(bidder1); // bidder1.id is now set

        bidder2 = new Bidder();
        bidder2.setUsername("bob_test");
        bidder2.setPasswordHash("hash");
        bidder2.setEmail("bob_test@test.local");
        userDAO.save(bidder2);

        // Create a Seller to own the item and auction.
        Seller seller = new Seller();
        seller.setUsername("seller_test");
        seller.setPasswordHash("hash");
        seller.setEmail("seller_test@test.local");
        userDAO.save(seller);

        // Persist an Electronics item owned by the seller.
        Electronics item = new Electronics();
        item.setName("Test Laptop");
        item.setDescription("A laptop for testing");
        item.setSellerId(seller.getId());
        item.setSellerName(seller.getUsername());
        itemDAO.save(item);

        // Persist a RUNNING auction for the item, starting 1 minute ago, ending in 2 hours.
        auction = new Auction();
        auction.setItem(item);
        auction.setStartingPrice(100.0);
        auction.setCurrentPrice(100.0);
        auction.setStartTime(LocalDateTime.now().minusMinutes(1));  // already started
        auction.setEndTime(LocalDateTime.now().plusHours(2));        // plenty of time
        auction.setStatus(AuctionStatus.RUNNING);
        auction.setSellerId(seller.getId());
        auction.setSellerName(seller.getUsername());
        auctionDAO.save(auction); // auction.id is now set
    }

    @AfterAll
    static void teardown() throws Exception {
        // Close the DatabaseManager connection so the OS can release the file lock.
        DatabaseManager.resetForTesting();
        System.clearProperty("auction.db.url");

        // On Windows, the SQLite file lock may persist briefly after close().
        // System.gc() encourages the JVM to finalize any lingering JDBC objects.
        System.gc();

        // Retry loop: wait up to 1 second for the lock to release before deleting.
        for (int i = 0; i < 10; i++) {
            try {
                Files.deleteIfExists(tempDb);
                // Also clean up WAL/SHM sidecar files (present if WAL mode was ever used).
                Files.deleteIfExists(Path.of(tempDb.toString() + "-wal"));
                Files.deleteIfExists(Path.of(tempDb.toString() + "-shm"));
                break; // success — exit the retry loop
            } catch (IOException ignored) {
                Thread.sleep(100); // wait 100ms and retry
            }
        }
    }

    // ── Test cases ────────────────────────────────────────────────────────────

    /**
     * Test 1: Place a valid bid.
     *
     * bidder1 bids $150 on an auction whose current price is $100 (the floor).
     * Verifies:
     *   - The returned BidTransaction has the correct amount and bidder.
     *   - The auction's currentPrice is updated in the database (re-read to confirm).
     *   - The auction's leadingBidderId is set to bidder1.
     */
    @Test @Order(1)
    void placeBid_valid_succeedsAndUpdatesPrice() {
        BidTransaction bid = bidService.placeBid(auction.getId(), 150.0, bidder1);

        // Verify the returned transaction.
        assertEquals(150.0, bid.getAmount(), 0.001, "Bid amount must be 150.0");
        assertEquals(bidder1.getId(), bid.getBidderId(), "Bid must be attributed to bidder1");

        // Re-read from DB to confirm the price was persisted.
        Auction updated = auctionService.getAuction(auction.getId());
        assertEquals(150.0, updated.getCurrentPrice(), 0.001, "DB current price must be 150.0");
        assertEquals(bidder1.getId(), updated.getLeadingBidderId(),
                "Leading bidder must be bidder1");
    }

    /**
     * Test 2: Bid below current price is rejected.
     *
     * After test 1, currentPrice = 150.  A bid of $50 must throw BidException
     * with a message explaining that the amount must exceed the current price.
     */
    @Test @Order(2)
    void placeBid_tooLow_throwsBidException() {
        // $50 is well below the current price of $150 — must be rejected.
        assertThrows(BidException.class,
                () -> bidService.placeBid(auction.getId(), 50.0, bidder2),
                "Bid below current price must throw BidException");
    }

    /**
     * Test 3: The leading bidder cannot bid again.
     *
     * After test 1, bidder1 is the leader at $150.  bidder1 trying to bid $200
     * must throw BidException — they're already winning and can't drive up
     * their own price.
     */
    @Test @Order(3)
    void placeBid_alreadyLeading_throwsBidException() {
        // bidder1 is the current leader (from test 1).
        assertThrows(BidException.class,
                () -> bidService.placeBid(auction.getId(), 200.0, bidder1),
                "Leading bidder must not be allowed to bid again");
    }

    /**
     * Test 4: A second bidder can outbid and become the new leader.
     *
     * bidder2 bids $200 — higher than bidder1's $150.  This must succeed and
     * transfer the lead to bidder2.
     */
    @Test @Order(4)
    void placeBid_secondBidder_succeedsAndBecomesLeader() {
        BidTransaction bid = bidService.placeBid(auction.getId(), 200.0, bidder2);

        assertEquals(200.0, bid.getAmount(), 0.001);

        // Confirm the leader changed in the database.
        Auction updated = auctionService.getAuction(auction.getId());
        assertEquals(bidder2.getId(), updated.getLeadingBidderId(),
                "bidder2 must now be the leading bidder");
    }

    /**
     * Test 5: setAutoBid() fires immediately when maxBid > currentPrice.
     *
     * After test 4, bidder2 leads at $200.  bidder1 registers an auto-bid
     * with maxBid=$300 and increment=$10.  The auto-bid should fire immediately
     * because $300 > $200 (current price).  After resolution:
     *   - bidder1 should be the leader (auto-bid outbid bidder2).
     *   - currentPrice should be $210 (200 + 10 increment).
     */
    @Test @Order(5)
    void setAutoBid_firesImmediately_whenCurrentPriceBelowMax() {
        bidService.setAutoBid(auction.getId(), 300.0, 10.0, bidder1);

        Auction updated = auctionService.getAuction(auction.getId());
        // Auto-bid must have raised the price above 200.
        assertTrue(updated.getCurrentPrice() > 200.0,
                "Auto-bid must raise the price above 200.0");
        // bidder1 must now be the leader.
        assertEquals(bidder1.getId(), updated.getLeadingBidderId(),
                "bidder1 must be the leader after auto-bid fires");
    }

    /**
     * Test 6: getBidHistory() returns all bids in ascending time order.
     *
     * At this point, the auction has had several bids (from tests 1, 4, 5).
     * The history must be non-empty and sorted earliest-first.
     *
     * Ascending order matters for the price chart: points must be left-to-right.
     */
    @Test @Order(6)
    void getBidHistory_returnsAllBids_inAscendingOrder() {
        var history = bidService.getBidHistory(auction.getId());

        assertFalse(history.isEmpty(),
                "Bid history must not be empty after several bids");

        // Verify ascending order: each bid must be at or after the previous one.
        for (int i = 1; i < history.size(); i++) {
            assertFalse(
                history.get(i).getTimestamp().isBefore(history.get(i - 1).getTimestamp()),
                "Bid history must be in ascending chronological order (index " + i + ")");
        }
    }
}
