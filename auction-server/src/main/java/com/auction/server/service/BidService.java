package com.auction.server.service;

import com.auction.server.dao.AuctionDAO;  // reads/writes auction price and status
import com.auction.server.dao.AutoBidDAO; // reads active auto-bids, marks exhausted ones inactive
import com.auction.server.dao.BidDAO;     // persists each accepted bid
import com.auction.server.exception.BidException; // thrown for rule violations
import com.auction.server.model.*;        // Auction, AuctionStatus, AutoBid, BidTransaction, User
import com.auction.server.observer.AuctionEventBus; // publishes BID_BROADCAST after each bid
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;           // timestamp for new bid records
import java.util.*;                       // PriorityQueue, List, Collections
import java.util.concurrent.ConcurrentHashMap;  // lock-free map for per-auction lock storage
import java.util.concurrent.locks.ReentrantLock; // fair mutex for concurrent bid safety

/**
 * Core bidding rules: validates offers, persists {@link com.auction.server.model.BidTransaction},
 * resolves competing {@link com.auction.server.model.AutoBid} rows, and notifies watchers.
 *
 * <p><b>Concurrency pattern:</b> {@link java.util.concurrent.ConcurrentHashMap} stores one
 * {@link java.util.concurrent.locks.ReentrantLock} per auction id. {@code fair=true} avoids starvation.
 * Only one thread may change a given auction’s price at a time, but different auctions stay parallel.</p>
 *
 * <p><b>Collections / generics:</b> {@link java.util.PriorityQueue} orders auto-bidders by rule
 * (highest max bid wins ties with earliest registration). After each manual bid, the service
 * may enqueue synthetic bids until the queue is stable.</p>
 *
 * <p><b>Decoupling:</b> After DB updates it calls {@link com.auction.server.observer.AuctionEventBus}
 * so {@link com.auction.server.network.ClientHandler} can push JSON without this class importing sockets.</p>
 */
public final class BidService {

    private static final Logger log = LoggerFactory.getLogger(BidService.class);

    private final AuctionDAO    auctionDAO;
    private final BidDAO        bidDAO;
    private final AutoBidDAO    autoBidDAO;
    private final AuctionService auctionService; // for anti-snipe + markRunning
    private final AuctionEventBus eventBus = AuctionEventBus.getInstance();

    // Each auction gets its own ReentrantLock. A lock is like a key to a room:
    // only the thread holding the key may change that auction's price.
    // ConcurrentHashMap is used because many client threads may ask for locks at the same time.
    private final ConcurrentHashMap<Long, ReentrantLock> auctionLocks = new ConcurrentHashMap<>();

    public BidService(AuctionDAO auctionDAO, BidDAO bidDAO,
                      AutoBidDAO autoBidDAO, AuctionService auctionService) {
        this.auctionDAO     = auctionDAO;
        this.bidDAO         = bidDAO;
        this.autoBidDAO     = autoBidDAO;
        this.auctionService = auctionService;
    }

    // Manual bid

    public BidTransaction placeBid(long auctionId, double amount, User bidder) {
        if (!bidder.canBid())
            throw new BidException("Your account type cannot place bids");

        ReentrantLock lock = lockFor(auctionId); // get or create the auction's lock
        // lock() waits here if another client is already changing this same auction.
        // Other auctions can still be processed because they use different locks.
        lock.lock();
        try {
            // Always re-read from DB inside the lock to get the latest price/status.
            Auction auction = auctionDAO.findById(auctionId)
                    .orElseThrow(() -> new BidException("Auction not found: " + auctionId));

            validateBidState(auction, amount, bidder.getId());
            auctionService.markRunning(auction);  // OPEN -> RUNNING on first bid

            // Persist the manual bid and push it immediately to watchers so the
            // live UI can show the same sequence that was written to the DB.
            BidTransaction bid = persistBid(auction, bidder.getId(),
                    bidder.getUsername(), amount, false);
            eventBus.publishBidPlaced(auction, bid);

            // Let competing auto-bidders respond (may fire multiple bids recursively).
            resolveAutoBids(auction);

            // Check anti-snipe: if bid arrived in last 30s, extend end time by 60s.
            auctionService.applyAntiSnipe(auction);

            return bid;
        } finally {
            // unlock() must run in finally so the auction is not left permanently locked after an error.
            lock.unlock();
        }
    }

    // Auto-bid registration

    public AutoBid setAutoBid(long auctionId, double maxBid, double increment, User bidder) {
        if (!bidder.canBid())
            throw new BidException("Your account type cannot place bids");
        if (maxBid <= 0 || increment <= 0)
            throw new BidException("maxBid and increment must be positive");

        ReentrantLock lock = lockFor(auctionId);
        // Auto-bid setup can immediately change the current price, so it uses the same per-auction lock as manual bids.
        lock.lock();
        try {
            Auction auction = auctionDAO.findById(auctionId)
                    .orElseThrow(() -> new BidException("Auction not found: " + auctionId));

            if (auction.getStatus() == AuctionStatus.FINISHED
                    || auction.getStatus() == AuctionStatus.CANCELED)
                throw new BidException("Auction is no longer active");

            // Build and persist the auto-bid configuration (UPSERT in the DAO).
            AutoBid ab = new AutoBid();
            ab.setAuctionId(auctionId);
            ab.setBidderId(bidder.getId());
            ab.setBidderName(bidder.getUsername());
            ab.setMaxBid(maxBid);
            ab.setIncrement(increment);
            ab.setRegisteredAt(LocalDateTime.now());
            autoBidDAO.save(ab);

            // If the new ceiling already beats the current price, fire immediately.
            if (maxBid > auction.getCurrentPrice()) {
                resolveAutoBids(auction);
                auctionService.applyAntiSnipe(auction);
            }

            return ab;
        } finally {
            lock.unlock();
        }
    }

    // Bid history

    public List<BidTransaction> getBidHistory(long auctionId) {
        return bidDAO.findByAuctionId(auctionId);
    }

    // Validation

    private void validateBidState(Auction auction, double amount, long bidderId) {
        if (auction.getStatus() == AuctionStatus.FINISHED
                || auction.getStatus() == AuctionStatus.CANCELED)
            throw new BidException("Auction is closed");
        if (LocalDateTime.now().isAfter(auction.getEndTime()))
            throw new BidException("Auction has already ended");
        if (amount <= auction.getCurrentPrice())
            throw new BidException(String.format(
                "Bid %.2f must exceed current price %.2f", amount, auction.getCurrentPrice()));
        if (auction.getLeadingBidderId() != null
                && auction.getLeadingBidderId() == bidderId)
            throw new BidException("You are already the highest bidder");
    }

    // Auto-bid resolution

    private void resolveAutoBids(Auction auction) {
        List<AutoBid> active = autoBidDAO.findActiveByAuctionId(auction.getId());
        if (active.isEmpty()) return;

        Long leaderId = auction.getLeadingBidderId();

        // Comparator: highest maxBid first; tie-break by earliest registeredAt.
        PriorityQueue<AutoBid> pq = new PriorityQueue<>((a, b) -> {
            int cmp = Double.compare(b.getMaxBid(), a.getMaxBid()); // DESC
            if (cmp != 0) return cmp;
            return a.getRegisteredAt().compareTo(b.getRegisteredAt()); // ASC
        });

        // Only add auto-bids that can still outbid the current price.
        for (AutoBid ab : active) {
            if (leaderId != null && ab.getBidderId() == leaderId) continue; // skip the leader
            if (ab.getMaxBid() > auction.getCurrentPrice()) pq.add(ab);
        }

        if (pq.isEmpty()) return; // nobody can outbid - done

        AutoBid winner = pq.poll(); // the auto-bidder with the highest ceiling
        // Their next bid: one increment above current price, capped at their ceiling.
        double newPrice = Math.min(
                auction.getCurrentPrice() + winner.getIncrement(),
                winner.getMaxBid());

        BidTransaction autoBidTx = persistBid(
                auction, winner.getBidderId(), winner.getBidderName(), newPrice, true);
        eventBus.publishBidPlaced(auction, autoBidTx);

        log.debug("Auto-bid: auction={} bidder={} price={}",
                auction.getId(), winner.getBidderName(), newPrice);

        // Recurse: the new leader's bid may prompt another auto-bidder to respond.
        resolveAutoBids(auction);
    }

    // Persistence helper

    private BidTransaction persistBid(Auction auction, long bidderId,
                                       String bidderName, double amount, boolean isAuto) {
        BidTransaction bt = new BidTransaction();
        bt.setAuctionId(auction.getId());
        bt.setBidderId(bidderId);
        bt.setBidderName(bidderName);
        bt.setAmount(amount);
        bt.setAutoBid(isAuto);
        bt.setTimestamp(LocalDateTime.now());
        bidDAO.save(bt); // persist to bid_transactions; sets bt.id

        // Update in-memory state first (fast, no DB round-trip).
        auction.setCurrentPrice(amount);
        auction.setLeadingBidderId(bidderId);
        auction.setLeadingBidderName(bidderName);

        // Persist the price change to DB.
        auctionDAO.updateCurrentPrice(auction.getId(), amount, bidderId);

        return bt;
    }

    // Lock management

    private ReentrantLock lockFor(long auctionId) {
        // computeIfAbsent performs "look up the lock, or create it once if missing" safely across threads.
        // true asks ReentrantLock to be fair, meaning waiting threads are served roughly in arrival order.
        return auctionLocks.computeIfAbsent(auctionId,
                k -> new ReentrantLock(true));
    }
}
