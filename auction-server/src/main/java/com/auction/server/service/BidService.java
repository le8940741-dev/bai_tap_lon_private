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
 * FILE ROLE: Core bidding engine — the most concurrency-sensitive class in the system.
 *
 * CONCURRENCY DESIGN — PER-AUCTION ReentrantLock:
 *   The fundamental problem: two clients can submit bids for the same auction
 *   at exactly the same time.  Without synchronisation:
 *     Thread A reads currentPrice=100, Thread B reads currentPrice=100,
 *     both see their bid of 110 as valid, both persist — now two "winners" exist.
 *   This is the "lost update" problem.
 *
 *   Solution: each auction has its own ReentrantLock stored in 'auctionLocks'.
 *   A bidder must acquire that lock before reading or writing the auction's price.
 *   Only one thread can hold the lock at a time — the check-then-update is atomic.
 *
 *   WHY PER-AUCTION (not a global lock):
 *     A global synchronized block would make all auctions compete for one lock.
 *     Bidding on auction #1 would block bidding on auction #2.
 *     Per-auction locks allow maximum throughput: auctions are independent.
 *
 *   ReentrantLock(true) = FAIR mode: threads acquire the lock in FIFO order,
 *   preventing a fast-spinning thread from starving slower ones.
 *
 * AUTO-BID RESOLUTION (PriorityQueue algorithm):
 *   After every manual bid, resolveAutoBids() fires:
 *     1. Load all active auto-bids for the auction from the DB.
 *     2. Filter out the current leader (they don't bid against themselves).
 *     3. Insert eligible auto-bids into a PriorityQueue ordered by:
 *          maxBid DESC (highest ceiling wins)
 *          registeredAt ASC (tie-break: earlier registration wins)
 *     4. Poll the top auto-bidder; compute newPrice = min(currentPrice + increment, maxBid).
 *     5. Persist the auto-bid as a BidTransaction (autoBid=true).
 *     6. Recurse: the new price may trigger a competing auto-bidder to respond.
 *   Recursion terminates when no auto-bidder can outbid the current price.
 *
 * CALLED BY: ClientHandler (PLACE_BID, SET_AUTO_BID, GET_BID_HISTORY messages)
 */
public final class BidService {

    private static final Logger log = LoggerFactory.getLogger(BidService.class);

    private final AuctionDAO    auctionDAO;
    private final BidDAO        bidDAO;
    private final AutoBidDAO    autoBidDAO;
    private final AuctionService auctionService; // for anti-snipe + markRunning
    private final AuctionEventBus eventBus = AuctionEventBus.getInstance();

    // Maps auctionId → its dedicated ReentrantLock.
    // ConcurrentHashMap.computeIfAbsent() creates the lock the first time it's needed.
    private final ConcurrentHashMap<Long, ReentrantLock> auctionLocks = new ConcurrentHashMap<>();

    public BidService(AuctionDAO auctionDAO, BidDAO bidDAO,
                      AutoBidDAO autoBidDAO, AuctionService auctionService) {
        this.auctionDAO     = auctionDAO;
        this.bidDAO         = bidDAO;
        this.autoBidDAO     = autoBidDAO;
        this.auctionService = auctionService;
    }

    // ── Manual bid ────────────────────────────────────────────────────────────

    /**
     * Place a manual bid on behalf of the authenticated user.
     *
     * Steps (all performed while holding the auction's ReentrantLock):
     *   1. Acquire the per-auction lock.
     *   2. Reload the auction from DB (get fresh state, not stale cache).
     *   3. Validate: bidder may bid, auction is not terminal, endTime has not
     *      passed, amount exceeds currentPrice, and the current leader is not
     *      bidding against themselves.
     *   4. Transition OPEN -> RUNNING on the first accepted bid.
     *   5. Persist the manual bid and broadcast it immediately.
     *   6. Run auto-bid resolution; each generated auto-bid is also persisted
     *      and broadcast in the order it is applied.
     *   7. Check anti-snipe after the price settles (may extend end time and
     *      reschedule the close task).
     *   8. Release the lock.
     *
     * @param auctionId the auction to bid on
     * @param amount    the proposed bid (must exceed currentPrice)
     * @param bidder    the authenticated Bidder placing the bid
     * @return the persisted BidTransaction (the manual bid, not any auto-bids)
     */
    public BidTransaction placeBid(long auctionId, double amount, User bidder) {
        if (!bidder.canBid())
            throw new BidException("Your account type cannot place bids");

        ReentrantLock lock = lockFor(auctionId); // get or create the auction's lock
        lock.lock();                              // ACQUIRE — blocks until no other thread holds it
        try {
            // Always re-read from DB inside the lock to get the latest price/status.
            Auction auction = auctionDAO.findById(auctionId)
                    .orElseThrow(() -> new BidException("Auction not found: " + auctionId));

            validateBidState(auction, amount, bidder.getId());
            auctionService.markRunning(auction);  // OPEN → RUNNING on first bid

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
            lock.unlock(); // RELEASE — always, even if an exception was thrown
        }
    }

    // ── Auto-bid registration ─────────────────────────────────────────────────

    /**
     * Register or update an auto-bid configuration for the authenticated user.
     *
     * If maxBid > currentPrice, fires resolveAutoBids() immediately so the
     * auto-bidder competes right away without waiting for a manual bid.
     *
     * @param auctionId the auction to auto-bid on
     * @param maxBid    ceiling — will never bid above this
     * @param increment how much to add to the competitor's bid each time
     * @param bidder    the authenticated Bidder
     * @return the persisted AutoBid configuration
     */
    public AutoBid setAutoBid(long auctionId, double maxBid, double increment, User bidder) {
        if (!bidder.canBid())
            throw new BidException("Your account type cannot place bids");
        if (maxBid <= 0 || increment <= 0)
            throw new BidException("maxBid and increment must be positive");

        ReentrantLock lock = lockFor(auctionId);
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

    // ── Bid history ───────────────────────────────────────────────────────────

    /**
     * Return all bids for an auction in ascending chronological order.
     * Called by ClientHandler for GET_BID_HISTORY — no lock needed (read-only).
     */
    public List<BidTransaction> getBidHistory(long auctionId) {
        return bidDAO.findByAuctionId(auctionId);
    }

    // ── Validation ────────────────────────────────────────────────────────────

    /**
     * Validate that a bid can be placed.  Must be called while holding the lock.
     *
     * Checks:
     *   - Auction must not be FINISHED or CANCELED.
     *   - Current time must be before endTime (belt-and-suspenders; scheduler also closes).
     *   - amount must be strictly greater than currentPrice.
     *   - The leading bidder cannot bid again (they're already winning).
     *
     * Deliberate note about current behaviour:
     *   This method does not enforce startTime. If an auction is still OPEN but
     *   passes the checks below, placeBid() will accept the bid and then call
     *   AuctionService.markRunning() to promote it to RUNNING.
     */
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

    // ── Auto-bid resolution ────────────────────────────────────────────────────

    /**
     * Resolve competing auto-bids after the current price changes.
     *
     * Algorithm:
     *   1. Load active auto-bids (registered auto-bidders who haven't been exhausted).
     *   2. Exclude the current leader — they don't bid against themselves.
     *   3. Filter to those whose maxBid > currentPrice (can still afford to bid).
     *   4. Build PriorityQueue: highest maxBid first, earlier registration breaks ties.
     *   5. Poll the winner; compute their next bid = min(currentPrice + increment, maxBid).
     *   6. Persist the auto BidTransaction and broadcast it immediately.
     *   7. Recurse for the new price.
     *   Recursion terminates when no eligible auto-bidder remains.
     *
     * @param auction the live Auction object whose currentPrice, leader fields,
     *                and watcher-visible state are updated in-place
     */
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

        if (pq.isEmpty()) return; // nobody can outbid — done

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

    // ── Persistence helper ─────────────────────────────────────────────────────

    /**
     * Persist a bid (manual or auto) and update the auction's price/leader.
     *
     * Updates both:
     *   - The in-memory Auction object (so subsequent operations in the same
     *     lock-holding block see the latest price without re-reading from DB).
     *   - The database row (so other server restarts and future reads are correct).
     *
     * @param isAuto true for auto-bid transactions, false for manual bids
     * @return the persisted BidTransaction with its DB-assigned id
     */
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

    // ── Lock management ────────────────────────────────────────────────────────

    /**
     * Get (or lazily create) the ReentrantLock for a given auction.
     *
     * computeIfAbsent is atomic in ConcurrentHashMap — if two threads call
     * lockFor(42) simultaneously, only one lock is created and both get the same one.
     *
     * @param auctionId the auction whose lock we need
     * @return the fair ReentrantLock for that auction
     */
    private ReentrantLock lockFor(long auctionId) {
        return auctionLocks.computeIfAbsent(auctionId,
                k -> new ReentrantLock(true)); // true = fair mode (FIFO)
    }
}
