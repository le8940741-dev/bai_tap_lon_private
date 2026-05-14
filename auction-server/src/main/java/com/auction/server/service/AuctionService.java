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
 * Owns the auction <b>lifecycle</b>: create rows, cancel, schedule automatic close, apply anti-snipe.
 *
 * <p><b>Scheduler API:</b> Uses {@link java.util.concurrent.ScheduledExecutorService} so each auction’s
 * {@code endTime} maps to a {@link java.util.concurrent.ScheduledFuture}. When the JVM restarts,
 * DAO reads repopulate timers so unfinished auctions still close.</p>
 *
 * <p><b>Interaction:</b> {@link com.auction.server.service.BidService} calls back into here for
 * {@code markRunning()} and time extensions so bid acceptance and time rules stay in one place.</p>
 */
public final class AuctionService {

    private static final Logger log = LoggerFactory.getLogger(AuctionService.class);

    public static final int ANTI_SNIPE_WINDOW_SECONDS = 30;

    public static final int ANTI_SNIPE_EXTENSION_SECONDS = 60;

    private final AuctionDAO auctionDAO;

    // The singleton event bus - used to broadcast lifecycle events (end, extend).
    private final AuctionEventBus eventBus = AuctionEventBus.getInstance();

    // Single-thread scheduler: all auction close events fire sequentially.
    // Daemon thread: won't block JVM shutdown.
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "auction-scheduler");
                t.setDaemon(true);
                return t;
            });

    // Maps auctionId -> the scheduled task that will close it.
    // ConcurrentHashMap because ClientHandler threads (reschedule on extension) and
    // the scheduler thread (remove on fire) both access it.
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> closeFutures =
            new ConcurrentHashMap<>();

    public AuctionService(AuctionDAO auctionDAO) {
        this.auctionDAO = auctionDAO;
        restoreSchedules(); // re-arm timers for in-progress auctions on startup
    }

    // Creation

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
                        ? AuctionStatus.OPEN     // future start time; initial "not started" state
                        : AuctionStatus.RUNNING  // start time already reached; accept bids immediately
        );

        auctionDAO.save(auction);       // persist and get id
        scheduleClose(auction);         // arm the timer
        log.info("Auction {} created for item '{}', ends {}", auction.getId(), item.getName(), endTime);
        return auction;
    }

    // Queries

    public Auction getAuction(long auctionId) {
        return auctionDAO.findById(auctionId)
                .orElseThrow(() -> new AuctionException("Auction not found: " + auctionId));
    }

    public List<Auction> getAllAuctions() { return auctionDAO.findAll(); }

    public List<Auction> getSellerAuctions(long sellerId) {
        return auctionDAO.findBySellerId(sellerId);
    }

    // Cancellation

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

    // Anti-sniping (called by BidService under the auction lock)

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

    // Status helpers (called by BidService)

    public void markRunning(Auction auction) {
        if (auction.getStatus() == AuctionStatus.OPEN) {
            auction.setStatus(AuctionStatus.RUNNING);
            auctionDAO.updateStatus(auction.getId(), AuctionStatus.RUNNING);
        }
    }

    public void closeAuction(long auctionId) {
        auctionDAO.findById(auctionId).ifPresent(auction -> {
            // Terminal states - nothing to do.
            if (auction.getStatus() == AuctionStatus.FINISHED
                    || auction.getStatus() == AuctionStatus.CANCELED
                    || auction.getStatus() == AuctionStatus.PAID) return;

            if (auction.getLeadingBidderId() != null) {
                // Someone bid - they win; transition to FINISHED.
                auctionDAO.updateWinner(auctionId,
                        auction.getLeadingBidderId(), AuctionStatus.FINISHED);
                auction.setStatus(AuctionStatus.FINISHED);
            } else {
                // No bids - cancel the auction.
                auctionDAO.updateStatus(auctionId, AuctionStatus.CANCELED);
                auction.setStatus(AuctionStatus.CANCELED);
            }
            eventBus.publishAuctionEnded(auction); // notify all watchers
            log.info("Auction {} closed. Winner: {}", auctionId, auction.getLeadingBidderName());
        });
    }

    // Scheduler management

    private void scheduleClose(Auction auction) {
        long delayMs = ChronoUnit.MILLIS.between(LocalDateTime.now(), auction.getEndTime());
        if (delayMs <= 0) {
            // End time is already past - close immediately (handles restart recovery).
            scheduler.submit(() -> closeAuction(auction.getId()));
            return;
        }
        ScheduledFuture<?> future = scheduler.schedule(
                () -> closeAuction(auction.getId()), delayMs, TimeUnit.MILLISECONDS);
        closeFutures.put(auction.getId(), future); // store for possible cancellation
    }

    private void rescheduleClose(Auction auction) {
        cancelScheduledClose(auction.getId());
        scheduleClose(auction);
    }

    private void cancelScheduledClose(long auctionId) {
        ScheduledFuture<?> f = closeFutures.remove(auctionId);
        if (f != null) f.cancel(false); // false = don't interrupt if already running
    }

    private void restoreSchedules() {
        List<Auction> open    = auctionDAO.findByStatus(AuctionStatus.OPEN);
        List<Auction> running = auctionDAO.findByStatus(AuctionStatus.RUNNING);
        open.forEach(this::scheduleClose);
        running.forEach(this::scheduleClose);
        log.info("Restored {} auction schedule(s) from database", open.size() + running.size());
    }
}
