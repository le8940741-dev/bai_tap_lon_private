package com.auction.server.service;

/**
 * Focused tests for anti-snipe math and cancellation rules on {@link AuctionService} with a mocked {@link com.auction.server.dao.AuctionDAO}.
 *
 * <p>Demonstrates Mockito’s {@link org.mockito.Mockito#verify(Object)} API to ensure DAO methods are invoked with expected arguments
 * after the service mutates in-memory auction fixtures.</p>
 */
import com.auction.server.dao.AuctionDAO;
import com.auction.server.exception.AuctionException;
import com.auction.server.model.*;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuctionServiceTest {

    AuctionDAO      mockAuctionDAO;  // mock - no database
    AuctionService  auctionService;

    // Reusable test fixtures - created once in setup().
    Seller  seller;  // the authenticated user who creates auctions
    Bidder  bidder;  // a non-seller used to test authorisation rejection
    Art     item;    // a pre-existing item (id already set) to auction

    // Stores the most recently saved auction so findById() can return it.
    // Updated by the save() mock answer whenever AuctionService calls auctionDAO.save().
    Auction lastSaved;

    @BeforeEach
    void setup() {
        mockAuctionDAO = Mockito.mock(AuctionDAO.class);

        // save() -> assign id=1 to the auction, store it for findById(), return it.
        when(mockAuctionDAO.save(any(Auction.class))).thenAnswer(inv -> {
            Auction a = inv.getArgument(0);
            a.setId(1L);
            lastSaved = a;    // capture so findById can return this instance
            return a;
        });

        // findById(1) -> return whatever was last saved.
        when(mockAuctionDAO.findById(1L)).thenAnswer(inv -> {
            return lastSaved == null
                    ? java.util.Optional.empty()
                    : java.util.Optional.of(lastSaved);
        });

        // findByStatus(any) -> empty list so AuctionService's restoreSchedules()
        // constructor call finds nothing to reschedule.
        when(mockAuctionDAO.findByStatus(any())).thenReturn(List.of());

        // Void update methods -> no-op (just let them return without doing anything).
        doNothing().when(mockAuctionDAO).updateStatus(anyLong(), any());
        doNothing().when(mockAuctionDAO).updateEndTime(anyLong(), any());
        doNothing().when(mockAuctionDAO).updateWinner(anyLong(), anyLong(), any());
        doNothing().when(mockAuctionDAO).updateCurrentPrice(anyLong(), anyDouble(), anyLong());

        // Create the service with the mock DAO.
        auctionService = new AuctionService(mockAuctionDAO);

        // Build fixtures.
        seller = new Seller();
        seller.setId(10L);
        seller.setUsername("test_seller");

        bidder = new Bidder();
        bidder.setId(20L);
        bidder.setUsername("test_bidder");

        item = new Art();
        item.setId(5L);
        item.setName("Test Artwork");
        item.setSellerId(seller.getId());
        item.setSellerName(seller.getUsername());
    }

    // Creation validation

    @Test @Order(1)
    void createAuction_nonSeller_throws() {
        assertThrows(AuctionException.class, () ->
            auctionService.createAuction(item, 100.0,
                LocalDateTime.now(), LocalDateTime.now().plusHours(1), bidder));
    }

    @Test @Order(2)
    void createAuction_negativePrice_throws() {
        assertThrows(AuctionException.class, () ->
            auctionService.createAuction(item, -10.0,
                LocalDateTime.now(), LocalDateTime.now().plusHours(1), seller));
    }

    @Test @Order(3)
    void createAuction_endBeforeStart_throws() {
        assertThrows(AuctionException.class, () ->
            auctionService.createAuction(item, 100.0,
                LocalDateTime.now().plusHours(2),  // start in 2h
                LocalDateTime.now().plusHours(1),  // end in 1h - BEFORE start
                seller));
    }

    @Test @Order(4)
    void createAuction_futureStart_statusIsOpen() {
        Auction a = auctionService.createAuction(item, 50.0,
            LocalDateTime.now().plusMinutes(10), // future start
            LocalDateTime.now().plusHours(2),
            seller);
        assertEquals(AuctionStatus.OPEN, a.getStatus(),
                "Auction with future startTime should start as OPEN");
        assertEquals(1L, a.getId(), "DAO should have assigned id=1");
    }

    @Test @Order(5)
    void createAuction_pastStart_statusIsRunning() {
        Auction a = auctionService.createAuction(item, 50.0,
            LocalDateTime.now().minusMinutes(5), // started 5 minutes ago
            LocalDateTime.now().plusHours(1),
            seller);
        assertEquals(AuctionStatus.RUNNING, a.getStatus(),
                "Auction with past startTime should start as RUNNING");
    }

    // Anti-sniping tests

    @Test @Order(6)
    void applyAntiSnipe_withinWindow_extendsEndTime() {
        // End in 20 seconds - inside the 30-second anti-snipe window.
        Auction a = auctionService.createAuction(item, 10.0,
            LocalDateTime.now().minusMinutes(5),
            LocalDateTime.now().plusSeconds(20),
            seller);

        LocalDateTime originalEnd = a.getEndTime();
        auctionService.applyAntiSnipe(a);  // should extend because 20s < 30s window

        assertTrue(a.getEndTime().isAfter(originalEnd),
                "End time should have been extended by anti-sniping");

        // Verify that the DAO was asked to persist the new end time.
        verify(mockAuctionDAO).updateEndTime(eq(1L), any(LocalDateTime.class));
    }

    @Test @Order(7)
    void applyAntiSnipe_outsideWindow_noChange() {
        // End in 10 minutes - well outside the 30-second window.
        Auction a = auctionService.createAuction(item, 10.0,
            LocalDateTime.now().minusMinutes(5),
            LocalDateTime.now().plusMinutes(10),
            seller);

        LocalDateTime originalEnd = a.getEndTime();
        auctionService.applyAntiSnipe(a);  // should be no-op

        assertEquals(originalEnd, a.getEndTime(),
                "End time should NOT change when bid is outside the anti-snipe window");

        // Verify that updateEndTime was NOT called (no extension).
        verify(mockAuctionDAO, never()).updateEndTime(anyLong(), any());
    }

    // Cancellation authorisation

    @Test @Order(8)
    void cancelAuction_byOwner_succeeds() {
        Auction a = auctionService.createAuction(item, 10.0,
            LocalDateTime.now().minusMinutes(1),
            LocalDateTime.now().plusHours(1),
            seller);

        assertDoesNotThrow(() -> auctionService.cancelAuction(a.getId(), seller));
        verify(mockAuctionDAO).updateStatus(eq(a.getId()), eq(AuctionStatus.CANCELED));
    }

    @Test @Order(9)
    void cancelAuction_byNonOwner_throws() {
        Auction a = auctionService.createAuction(item, 10.0,
            LocalDateTime.now().minusMinutes(1),
            LocalDateTime.now().plusHours(1),
            seller);

        // bidder.id (20) != seller.id (10), and bidder.role == BIDDER (not ADMIN).
        assertThrows(AuctionException.class,
            () -> auctionService.cancelAuction(a.getId(), bidder));

        // The DAO's updateStatus must NOT have been called - we blocked before it.
        verify(mockAuctionDAO, never()).updateStatus(anyLong(), eq(AuctionStatus.CANCELED));
    }
}
