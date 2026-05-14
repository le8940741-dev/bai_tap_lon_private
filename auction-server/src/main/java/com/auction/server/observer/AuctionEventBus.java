package com.auction.server.observer;

import com.auction.server.model.Auction;
import com.auction.server.model.BidTransaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Central place that remembers which {@link AuctionObserver}s care about which auction id.
 *
 * <p><b>Singleton + decoupling:</b> Services call {@link #publishBidPlaced} instead of
 * looping over sockets. That keeps {@link com.auction.server.service.BidService} free of
 * networking code — a classic “subject” in the Observer pattern.</p>
 *
 * <p><b>Concurrency:</b> {@link ConcurrentHashMap} maps auction id → thread-safe set of observers.
 * {@link #fan} copies the set and submits one runnable per observer to {@code notifyPool} so a
 * slow client write does not block the bidder’s request thread.</p>
 *
 * <p><b>Generics:</b> {@code ConcurrentHashMap<Long, Set<AuctionObserver>>} uses Java generics
 * to enforce that keys are {@link Long} ids and values are sets of observers.</p>
 */
public final class AuctionEventBus {

    private static final Logger log = LoggerFactory.getLogger(AuctionEventBus.class);

    // Singleton

    // volatile means "when one thread writes this field, other threads must see the newest value."
    // That matters here because client threads can ask for the event bus at the same time.
    private static volatile AuctionEventBus instance;

    private AuctionEventBus() {}

    public static AuctionEventBus getInstance() {
        if (instance == null) {
            // synchronized makes the first creation of the singleton happen one thread at a time.
            // After the object exists, callers skip this block and just reuse the same bus.
            synchronized (AuctionEventBus.class) {
                if (instance == null) instance = new AuctionEventBus();
            }
        }
        return instance;
    }

    // State

    // ConcurrentHashMap is a Map designed for many threads using it at once.
    // Here, one client thread may subscribe while another client thread is placing a bid.
    private final ConcurrentHashMap<Long, Set<AuctionObserver>> watchers =
            new ConcurrentHashMap<>();

    // This pool sends notifications on background threads. A slow client write should not
    // make the bidder who placed the bid wait for every watcher to receive the update.
    private final ExecutorService notifyPool = Executors.newCachedThreadPool(r -> {
        // Naming the thread helps when reading logs or debugger thread lists.
        Thread t = new Thread(r, "notify-pool");
        t.setDaemon(true); // daemon = dies when main thread dies; won't block JVM shutdown
        return t;
    });

    // Subscription management

    public void subscribe(long auctionId, AuctionObserver observer) {
        // computeIfAbsent is a safe "get or create" operation for ConcurrentHashMap.
        // It prevents two threads from accidentally creating two separate watcher sets for the same auction.
        watchers.computeIfAbsent(auctionId,
                k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(observer);
        log.debug("Observer subscribed to auction {}", auctionId);
    }

    public void unsubscribe(long auctionId, AuctionObserver observer) {
        Set<AuctionObserver> set = watchers.get(auctionId);
        if (set != null) set.remove(observer);
    }

    public void unsubscribeAll(AuctionObserver observer) {
        // ConcurrentHashMap allows this loop while other threads are subscribing or unsubscribing.
        // The loop sees a safe current view of the values instead of throwing a modification error.
        watchers.values().forEach(set -> set.remove(observer));
    }

    // Publication

    public void publishBidPlaced(Auction auction, BidTransaction bid) {
        fan(auction.getId(), obs -> obs.onBidPlaced(auction, bid));
    }

    public void publishAuctionEnded(Auction auction) {
        fan(auction.getId(), obs -> obs.onAuctionEnded(auction));
    }

    public void publishAuctionExtended(Auction auction) {
        fan(auction.getId(), obs -> obs.onAuctionExtended(auction));
    }

    // Internal fan-out

    private void fan(long auctionId, ObserverAction action) {
        Set<AuctionObserver> set = watchers.get(auctionId);
        if (set == null || set.isEmpty()) return;
        for (AuctionObserver obs : set) {
            // Each observer gets its own Runnable submitted to the pool.
            // Runnable is just "a block of code a thread can run later."
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
