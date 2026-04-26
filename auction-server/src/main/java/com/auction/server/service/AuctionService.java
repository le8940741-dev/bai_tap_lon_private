package com.auction.server.service;

import com.auction.server.dao.AuctionDAO;          // persistence for auctions
import com.auction.server.exception.AuctionException; // business rule violations
import com.auction.server.model.*;                 // Auction, AuctionStatus, Item, User, UserRole
import com.auction.server.observer.AuctionEventBus; // publishes lifecycle events to watchers
import org.slf4j.Logger;                           // structured logging
import org.slf4j.LoggerFactory;                    // creates a Logger for this class

import java.time.LocalDateTime;                    // wall-clock timestamps
import java.time.temporal.ChronoUnit;              // ChronoUnit.SECONDS.between() for time differences
import java.util.List;                             // return type for list queries
import java.util.concurrent.*;                     // ScheduledExecutorService, ScheduledFuture, TimeUnit

/**
 * FILE ROLE: Manages auction lifecycle — creation, scheduling, anti-sniping, and closure.
 *
 * TWO RESPONSIBILITIES (deliberately kept together because they are tightly coupled):
 *   1. CRUD operations: createAuction(), getAuction(), getAllAuctions(), cancelAuction()
 *   2. Timer management: every auction gets a ScheduledFuture that fires closeAuction()
 *      at the correct end time.
 *
 * SCHEDULER:
 *   A single-thread ScheduledExecutorService fires closeAuction() for each auction
 *   at the right time.  Using a single thread avoids any concurrency within the
 *   scheduler itself — tasks queue up and execute one at a time.
 *
 *   ScheduledFutures are stored in 'closeFutures' keyed by auctionId so that
 *   anti-sniping (applyAntiSnipe) can cancel and reschedule the future when
 *   the end time is extended.  Without this, the original task would fire at the
 *   old end time even though the auction had been extended.
 *
 * ANTI-SNIPING ALGORITHM:
 *   If a bid arrives within ANTI_SNIPE_WINDOW_SECONDS (30) of the end time,
 *   the end time is extended by ANTI_SNIPE_EXTENSION_SECONDS (60).
 *   applyAntiSnipe() is called by BidService after every accepted bid,
 *   while BidService still holds the per-auction ReentrantLock.
 *
 * SERVER RESTART RECOVERY:
 *   restoreSchedules() is called from the constructor.  It queries for all
 *   OPEN and RUNNING auctions and reschedules their close tasks.
 *   Auctions whose endTime is in the past are closed immediately.
 *
 * CALLED BY: ClientHandler, BidService
 */
public final class AuctionService {

    private static final Logger log = LoggerFactory.getLogger(AuctionService.class);

    /** Bids within this many seconds of end time trigger anti-sniping. */
    public static final int ANTI_SNIPE_WINDOW_SECONDS = 30;

    /** How many seconds to add to the end time when anti-sniping fires. */
    public static final int ANTI_SNIPE_EXTENSION_SECONDS = 60;

    private final AuctionDAO auctionDAO;

    // The singleton event bus — used to broadcast lifecycle events (end, extend).
    private final AuctionEventBus eventBus = AuctionEventBus.getInstance();

    // Single-thread scheduler: all auction close events fire sequentially.
    // Daemon thread: won't block JVM shutdown.
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "auction-scheduler");
                t.setDaemon(true);
                return t;
            });

    // Maps auctionId → the scheduled task that will close it.
    // ConcurrentHashMap because ClientHandler threads (reschedule on extension) and
    // the scheduler thread (remove on fire) both access it.
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> closeFutures =
            new ConcurrentHashMap<>();

    public AuctionService(AuctionDAO auctionDAO) {
        this.auctionDAO = auctionDAO;
        restoreSchedules(); // re-arm timers for in-progress auctions on startup
    }

    // ── Creation ──────────────────────────────────────────────────────────────

    /**
     * Create and persist a new auction, then schedule its automatic close task.
     *
     * Initial status:
     *   - OPEN if startTime is in the future (bids not yet accepted)
     *   - RUNNING if startTime is now or in the past (immediately open for bids)
     *
     * @param item         the already-persisted item being auctioned
     * @param startingPrice floor price; bids must exceed this
     * @param startTime    when bidding opens
     * @param endTime      when the auction auto-closes
     * @param seller       the Seller creating this auction
     */
    public Auction createAuction(Item item, double startingPrice,
                                  LocalDateTime startTime, LocalDateTime endTime,
                                  User seller) {
        if (seller.getRole() != UserRole.SELLER)
            throw new AuctionException("Only sellers can create auctions");
        if (startingPrice <= 0)
            throw new AuctionException("Starting price must be positive");
        if (!endTime.isAfter(startTime))
            throw new AuctionException("End time must be after start time");

        Auction auction = new Auction();
        auction.setItem(item);
        auction.setStartingPrice(startingPrice);
        auction.setCurrentPrice(startingPrice); // before any bids, price = floor
        auction.setStartTime(startTime);
        auction.setEndTime(endTime);
        auction.setSellerId(seller.getId());
        auction.setSellerName(seller.getUsername());
        auction.setStatus(
                LocalDateTime.now().isBefore(startTime)
                        ? AuctionStatus.OPEN     // future start — not yet accepting bids
                        : AuctionStatus.RUNNING  // past start — accepting bids immediately
        );

        auctionDAO.save(auction);       // persist and get id
        scheduleClose(auction);         // arm the timer
        log.info("Auction {} created for item '{}', ends {}", auction.getId(), item.getName(), endTime);
        return auction;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /** Fetch one auction by id; throws AuctionException if not found. */
    public Auction getAuction(long auctionId) {
        return auctionDAO.findById(auctionId)
                .orElseThrow(() -> new AuctionException("Auction not found: " + auctionId));
    }

    /** Return all auctions for the main list (newest first). */
    public List<Auction> getAllAuctions() { return auctionDAO.findAll(); }

    /** Return all auctions owned by a specific seller (for the Seller Dashboard). */
    public List<Auction> getSellerAuctions(long sellerId) {
        return auctionDAO.findBySellerId(sellerId);
    }

    // ── Cancellation ──────────────────────────────────────────────────────────

    /**
     * Cancel an auction before it finishes.
     * Allowed if requester is the seller who created it, or an Admin.
     * Not allowed once the auction is FINISHED or PAID.
     */
    public void cancelAuction(long auctionId, User requester) {
        Auction auction = getAuction(auctionId);
        if (auction.getSellerId() != requester.getId()
                && requester.getRole() != UserRole.ADMIN) {
            throw new AuctionException("Not authorised to cancel this auction");
        }
        if (auction.getStatus() == AuctionStatus.FINISHED
                || auction.getStatus() == AuctionStatus.PAID) {
            throw new AuctionException("Cannot cancel a finished auction");
        }
        auctionDAO.updateStatus(auctionId, AuctionStatus.CANCELED);
        auction.setStatus(AuctionStatus.CANCELED);
        cancelScheduledClose(auctionId); // prevent the scheduler from re-processing this
    }

    // ── Anti-sniping (called by BidService under the auction lock) ─────────────

    /**
     * Check whether the most recent bid arrived within the anti-snipe window.
     * If so, extend the end time and reschedule the close task.
     *
     * This method is called by BidService while it holds the per-auction
     * ReentrantLock, so no concurrent bid can change the price between
     * the check and the extension.
     *
     * @param auction the auction with its current (pre-extension) end time
     */
    public void applyAntiSnipe(Auction auction) {
        LocalDateTime now = LocalDateTime.now();
        long secondsLeft = ChronoUnit.SECONDS.between(now, auction.getEndTime());

        // Only extend if there's still time left AND we're inside the window.
        if (secondsLeft > 0 && secondsLeft <= ANTI_SNIPE_WINDOW_SECONDS) {
            LocalDateTime newEnd = auction.getEndTime()
                    .plusSeconds(ANTI_SNIPE_EXTENSION_SECONDS);
            auction.setEndTime(newEnd);               // update in-memory object
            auctionDAO.updateEndTime(auction.getId(), newEnd); // persist to DB
            rescheduleClose(auction);                 // cancel old task, schedule new one
            eventBus.publishAuctionExtended(auction); // broadcast to all watchers
            log.info("Anti-snipe: auction {} extended to {}", auction.getId(), newEnd);
        }
    }

    // ── Status helpers (called by BidService) ─────────────────────────────────

    /**
     * Transition an OPEN auction to RUNNING when its first bid arrives.
     * Called by BidService.placeBid() before persisting the bid.
     * No-op if the auction is already RUNNING.
     */
    public void markRunning(Auction auction) {
        if (auction.getStatus() == AuctionStatus.OPEN) {
            auction.setStatus(AuctionStatus.RUNNING);
            auctionDAO.updateStatus(auction.getId(), AuctionStatus.RUNNING);
        }
    }

    /**
     * Close an auction when its scheduled task fires.
     * If there was a leading bidder, they become the winner (FINISHED).
     * If nobody bid, the auction is CANCELED.
     * Either way, the event bus broadcasts the final state to watchers.
     *
     * Re-entrancy guard: if the auction is already in a terminal state
     * (FINISHED, CANCELED, PAID), this is a no-op — prevents double-close
     * on server restart when an auction's endTime is already in the past.
     */
    public void closeAuction(long auctionId) {
        auctionDAO.findById(auctionId).ifPresent(auction -> {
            // Terminal states — nothing to do.
            if (auction.getStatus() == AuctionStatus.FINISHED
                    || auction.getStatus() == AuctionStatus.CANCELED
                    || auction.getStatus() == AuctionStatus.PAID) return;

            if (auction.getLeadingBidderId() != null) {
                // Someone bid — they win; transition to FINISHED.
                auctionDAO.updateWinner(auctionId,
                        auction.getLeadingBidderId(), AuctionStatus.FINISHED);
                auction.setStatus(AuctionStatus.FINISHED);
            } else {
                // No bids — cancel the auction.
                auctionDAO.updateStatus(auctionId, AuctionStatus.CANCELED);
                auction.setStatus(AuctionStatus.CANCELED);
            }
            eventBus.publishAuctionEnded(auction); // notify all watchers
            log.info("Auction {} closed. Winner: {}", auctionId, auction.getLeadingBidderName());
        });
    }

    // ── Scheduler management ──────────────────────────────────────────────────

    /** Schedule a future call to closeAuction() at auction.endTime. */
    private void scheduleClose(Auction auction) {
        long delayMs = ChronoUnit.MILLIS.between(LocalDateTime.now(), auction.getEndTime());
        if (delayMs <= 0) {
            // End time is already past — close immediately (handles restart recovery).
            scheduler.submit(() -> closeAuction(auction.getId()));
            return;
        }
        ScheduledFuture<?> future = scheduler.schedule(
                () -> closeAuction(auction.getId()), delayMs, TimeUnit.MILLISECONDS);
        closeFutures.put(auction.getId(), future); // store for possible cancellation
    }

    /** Cancel the existing close task and schedule a new one (called after anti-snipe). */
    private void rescheduleClose(Auction auction) {
        cancelScheduledClose(auction.getId());
        scheduleClose(auction);
    }

    /** Cancel a scheduled close task without closing the auction (used by cancelAuction). */
    private void cancelScheduledClose(long auctionId) {
        ScheduledFuture<?> f = closeFutures.remove(auctionId);
        if (f != null) f.cancel(false); // false = don't interrupt if already running
    }

    /**
     * Re-arm close timers for OPEN and RUNNING auctions after server restart.
     * Without this, auctions created before the server restart would never close.
     */
    private void restoreSchedules() {
        List<Auction> open    = auctionDAO.findByStatus(AuctionStatus.OPEN);
        List<Auction> running = auctionDAO.findByStatus(AuctionStatus.RUNNING);
        open.forEach(this::scheduleClose);
        running.forEach(this::scheduleClose);
        log.info("Restored {} auction schedule(s) from database", open.size() + running.size());
    }
}
