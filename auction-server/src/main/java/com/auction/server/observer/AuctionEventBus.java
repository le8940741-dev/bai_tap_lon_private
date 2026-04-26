package com.auction.server.observer;

import com.auction.server.model.Auction;      // the auction whose event we are publishing
import com.auction.server.model.BidTransaction; // the bid that triggered a BID event
import org.slf4j.Logger;                      // structured logging API
import org.slf4j.LoggerFactory;               // creates a Logger for this class

import java.util.Collections;                 // Collections.newSetFromMap — thread-safe Set factory
import java.util.Set;                         // Set of observers per auction
import java.util.concurrent.ConcurrentHashMap; // lock-free hash map for per-auction watcher sets
import java.util.concurrent.ExecutorService;  // thread pool for async notification dispatch
import java.util.concurrent.Executors;        // factory for thread pool implementations

/**
 * FILE ROLE: Central event bus — the "Subject" in the Observer pattern.
 *
 * PATTERN: Observer (GoF) + Singleton (GoF)
 *
 * RESPONSIBILITIES:
 *   1. Maintain per-auction sets of AuctionObserver subscribers.
 *   2. Provide subscribe/unsubscribe methods for ClientHandler.
 *   3. Accept publish calls from BidService and AuctionService.
 *   4. Fan out each event to all registered observers asynchronously.
 *
 * WHY SINGLETON:
 *   Every service and every ClientHandler needs to talk to the same bus.
 *   A singleton avoids passing the bus through constructors everywhere.
 *   Double-checked locking ensures thread-safe lazy initialisation.
 *
 * DATA STRUCTURE — WHY ConcurrentHashMap + newSetFromMap:
 *   Standard HashSet is not thread-safe.  We need concurrent reads (iterating
 *   during fan-out) and concurrent writes (subscribe/unsubscribe from different
 *   client threads).
 *
 *   ConcurrentHashMap.newSetFromMap() creates a Set backed by a ConcurrentHashMap,
 *   giving us O(1) add/remove/contains with no explicit locking.
 *
 *   The outer map (auctionId → Set<Observer>) also uses ConcurrentHashMap so that
 *   two clients subscribing to different auctions simultaneously never block each other.
 *
 * WHY ASYNC NOTIFICATION:
 *   BidService holds a per-auction ReentrantLock while calling publishBidPlaced().
 *   If notification were synchronous, a slow or dead client (blocked TCP write)
 *   would hold the auction lock, preventing ALL further bids on that auction.
 *
 *   By dispatching notifications to a cached thread pool, the lock is released
 *   immediately after publish() returns.  Each client's write happens on a pool
 *   thread — slow clients only block themselves, not the entire auction.
 */
public final class AuctionEventBus {

    private static final Logger log = LoggerFactory.getLogger(AuctionEventBus.class);

    // ── Singleton ─────────────────────────────────────────────────────────────

    // volatile ensures that when one thread writes the reference, all other threads
    // see the new value immediately (no stale CPU cache).
    private static volatile AuctionEventBus instance;

    private AuctionEventBus() {}

    /**
     * Double-checked locking singleton.
     * The outer null check avoids locking on every call after initialisation.
     * The inner null check (inside synchronized) prevents a race where two threads
     * both pass the outer check before either creates the instance.
     */
    public static AuctionEventBus getInstance() {
        if (instance == null) {
            synchronized (AuctionEventBus.class) {
                if (instance == null) instance = new AuctionEventBus();
            }
        }
        return instance;
    }

    // ── State ─────────────────────────────────────────────────────────────────

    // Maps auctionId → set of all ClientHandlers currently watching that auction.
    // ConcurrentHashMap: multiple threads can read/write different keys simultaneously.
    private final ConcurrentHashMap<Long, Set<AuctionObserver>> watchers =
            new ConcurrentHashMap<>();

    // Cached thread pool: creates a new thread per notification burst, reuses idle threads.
    // Daemon threads: won't prevent JVM shutdown if the server process is killed.
    private final ExecutorService notifyPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "notify-pool");
        t.setDaemon(true); // daemon = dies when main thread dies; won't block JVM shutdown
        return t;
    });

    // ── Subscription management ───────────────────────────────────────────────

    /**
     * Register an observer to receive events for the given auction.
     * Called by ClientHandler when it receives a WATCH_AUCTION message.
     *
     * computeIfAbsent: atomically creates the Set if this is the first watcher
     * for this auction, then adds the observer.  Thread-safe with no explicit lock.
     *
     * @param auctionId the auction to subscribe to
     * @param observer  the ClientHandler to notify (one per connected client)
     */
    public void subscribe(long auctionId, AuctionObserver observer) {
        watchers.computeIfAbsent(auctionId,
                k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(observer);
        log.debug("Observer subscribed to auction {}", auctionId);
    }

    /**
     * Unregister an observer from a specific auction.
     * Called by ClientHandler when it receives UNWATCH_AUCTION,
     * or when the user navigates away from the detail screen.
     *
     * @param auctionId the auction to unsubscribe from
     * @param observer  the ClientHandler to remove
     */
    public void unsubscribe(long auctionId, AuctionObserver observer) {
        Set<AuctionObserver> set = watchers.get(auctionId);
        if (set != null) set.remove(observer);
    }

    /**
     * Remove an observer from ALL auction watcher sets.
     * Called by ClientHandler when the client disconnects (IOException in readLoop).
     * Without this, the dead ClientHandler would remain in watcher sets, causing
     * a NullPointerException or silent write failure on the next broadcast.
     *
     * @param observer the disconnected ClientHandler to remove everywhere
     */
    public void unsubscribeAll(AuctionObserver observer) {
        // Iterating ConcurrentHashMap values is safe — it uses a snapshot view.
        watchers.values().forEach(set -> set.remove(observer));
    }

    // ── Publication ───────────────────────────────────────────────────────────

    /**
     * Publish a "bid placed" event to all watchers of the auction.
     * Called by BidService after persisting a bid and updating the auction price.
     */
    public void publishBidPlaced(Auction auction, BidTransaction bid) {
        fan(auction.getId(), obs -> obs.onBidPlaced(auction, bid));
    }

    /**
     * Publish an "auction ended" event to all watchers.
     * Called by AuctionService.closeAuction() when the scheduler fires.
     */
    public void publishAuctionEnded(Auction auction) {
        fan(auction.getId(), obs -> obs.onAuctionEnded(auction));
    }

    /**
     * Publish an "auction extended" event to all watchers.
     * Called by AuctionService.applyAntiSnipe() when a late bid arrives.
     */
    public void publishAuctionExtended(Auction auction) {
        fan(auction.getId(), obs -> obs.onAuctionExtended(auction));
    }

    // ── Internal fan-out ──────────────────────────────────────────────────────

    /**
     * Dispatch an action to every observer watching the given auction.
     * Each observer is notified on a separate thread pool task so that a
     * slow/blocked write to one client never delays notification to others.
     *
     * Exceptions from individual observers are caught and logged rather than
     * propagated — one dead client must not prevent others from being notified.
     *
     * @param auctionId  the auction whose watchers receive this event
     * @param action     a lambda that calls the appropriate observer method
     */
    private void fan(long auctionId, ObserverAction action) {
        Set<AuctionObserver> set = watchers.get(auctionId);
        if (set == null || set.isEmpty()) return;
        for (AuctionObserver obs : set) {
            // Each observer gets its own Runnable submitted to the pool.
            notifyPool.submit(() -> {
                try {
                    action.apply(obs);
                } catch (Exception e) {
                    log.warn("Observer notification failed for auction {}: {}", auctionId, e.getMessage());
                }
            });
        }
    }

    // Functional interface used only internally to make the fan() method generic.
    @FunctionalInterface
    private interface ObserverAction {
        void apply(AuctionObserver obs);
    }
}
