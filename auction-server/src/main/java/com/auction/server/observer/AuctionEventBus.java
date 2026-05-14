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

    // volatile ensures that when one thread writes the reference, all other threads
    // see the new value immediately (no stale CPU cache).
    private static volatile AuctionEventBus instance;

    private AuctionEventBus() {}

    public static AuctionEventBus getInstance() {
        if (instance == null) {
            synchronized (AuctionEventBus.class) {
                if (instance == null) instance = new AuctionEventBus();
            }
        }
        return instance;
    }

    // State

    // Maps auctionId -> set of all ClientHandlers currently watching that auction.
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

    // Subscription management

    public void subscribe(long auctionId, AuctionObserver observer) {
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
        // Iterating ConcurrentHashMap values is safe - it uses a snapshot view.
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
