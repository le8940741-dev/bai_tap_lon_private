package com.auction.client.network;

// ── Wire types (shared with server via auction-common) ────────────────────────
import com.auction.common.protocol.Message;      // TCP envelope for every message
import com.auction.common.protocol.MessageType;  // used to route broadcasts by type
import com.auction.common.request.Responses.AuctionExtendedNotice; // anti-snipe broadcast payload
import com.auction.common.request.Responses.BidResponse;           // bid event broadcast payload
import com.auction.common.dto.AuctionDTO;        // auction end broadcast payload

// ── JSON ──────────────────────────────────────────────────────────────────────
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

// ── JavaFX thread utility ─────────────────────────────────────────────────────
import javafx.application.Platform; // Platform.runLater() — schedules work on the FX thread

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// ── Java I/O and networking ───────────────────────────────────────────────────
import java.io.*;   // BufferedReader, InputStreamReader, PrintWriter, IOException
import java.net.Socket; // the TCP connection to the server

// ── Java concurrency ──────────────────────────────────────────────────────────
import java.util.concurrent.*;  // CompletableFuture, ConcurrentHashMap, ExecutorService, Executors, TimeUnit
import java.util.function.Consumer; // used in the BroadcastListener functional design

/**
 * FILE ROLE: Manages the single persistent TCP connection from client to server.
 *
 * TWO COMMUNICATION PATTERNS in one class:
 *
 *   1. REQUEST-RESPONSE (CompletableFuture correlation):
 *      - send(msg) stores a CompletableFuture keyed by msg.requestId in 'pending'.
 *      - The reader thread sees a response with the matching requestId and
 *        calls future.complete(response), waking up the waiting controller.
 *      - Controllers call: conn.send(msg).whenCompleteAsync((response, ex) -> ...)
 *        — non-blocking; the callback runs when the server replies.
 *
 *   2. SERVER-PUSH BROADCASTS (BroadcastListener):
 *      - The server sends BID_BROADCAST / AUCTION_END_BROADCAST / AUCTION_EXTENDED
 *        with a brand-new requestId that no pending future is waiting for.
 *      - route() sees no matching future → calls dispatchBroadcast(msg).
 *      - dispatchBroadcast uses Platform.runLater() to call the registered
 *        BroadcastListener on the JavaFX Application Thread.
 *      - AuctionDetailController sets itself as the listener when it opens an auction.
 *
 * WHY Platform.runLater():
 *   JavaFX requires all UI updates to happen on the JavaFX Application Thread.
 *   The reader thread (server-reader) is NOT the FX thread — it's a background
 *   daemon thread.  Platform.runLater() schedules the callback to run on the FX
 *   thread, where it's safe to update labels, tables, and chart data.
 *
 * WHY CompletableFuture (not synchronized blocking):
 *   Blocking the FX thread waiting for a network response would freeze the UI.
 *   CompletableFuture lets us fire-and-forget on the FX thread and handle the
 *   response in a callback — the UI stays responsive during the round-trip.
 */
public final class ServerConnection {

    private static final Logger log = LoggerFactory.getLogger(ServerConnection.class);

    /**
     * Callback interface for server-push broadcasts.
     * AuctionDetailController implements this and sets itself via setBroadcastListener().
     * Only one listener is active at a time (the currently open detail screen).
     */
    public interface BroadcastListener {
        /** Called (on FX thread) when a new bid is placed in the watched auction. */
        void onBidBroadcast(BidResponse bidResponse);

        /** Called (on FX thread) when the watched auction reaches FINISHED state. */
        void onAuctionEnded(AuctionDTO auction);

        /** Called (on FX thread) when anti-sniping extends the watched auction's end time. */
        void onAuctionExtended(AuctionExtendedNotice notice);
    }

    // Thread-safe Gson — reused for all serialisation/deserialisation.
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    private Socket socket;      // the TCP connection; null before connect()
    private PrintWriter out;    // writes newline-delimited JSON to the socket

    // Maps requestId (UUID string) → CompletableFuture waiting for the response.
    // ConcurrentHashMap: reader thread completes futures; FX thread adds/removes them.
    private final ConcurrentHashMap<String, CompletableFuture<Message>> pending =
            new ConcurrentHashMap<>();

    // The currently registered broadcast listener (may be null if no detail screen is open).
    // volatile: written by the FX thread (controller sets it), read by the reader thread.
    private volatile BroadcastListener broadcastListener;

    // Single background thread that reads lines from the server socket.
    // Daemon: exits when the JVM exits; won't block application shutdown.
    private final ExecutorService readerThread =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "server-reader");
                t.setDaemon(true);
                return t;
            });

    // ── Connection management ─────────────────────────────────────────────────

    /**
     * Open the TCP connection and start the reader thread.
     * Called once from ClientMain before showing any UI.
     *
     * @param host the server hostname or IP address (default "localhost")
     * @param port the server TCP port (default 9090)
     * @throws IOException if the connection cannot be established
     */
    public void connect(String host, int port) throws IOException {
        socket = new Socket(host, port);
        out    = new PrintWriter(
                new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
        // Start the background reader; it will block on readLine() until data arrives.
        readerThread.submit(this::readLoop);
        log.info("Connected to {}:{}", host, port);
    }

    /** Close the socket (triggers an IOException in readLoop, ending the reader thread). */
    public void disconnect() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    /** True if the socket is open and connected. */
    public boolean isConnected() {
        return socket != null && !socket.isClosed();
    }

    /**
     * Set the broadcast listener for server-push events.
     * Called by AuctionDetailController when it opens a detail screen,
     * and cleared (set to null) when the controller navigates away.
     */
    public void setBroadcastListener(BroadcastListener listener) {
        this.broadcastListener = listener;
    }

    // ── Request-response API ──────────────────────────────────────────────────

    /**
     * Send a Message to the server and return a CompletableFuture for its response.
     *
     * Steps:
     *   1. Create a CompletableFuture and store it in 'pending' keyed by requestId.
     *   2. Serialise the Message to JSON and write it to the socket.
     *   3. Return the future — the caller attaches a whenCompleteAsync callback.
     *   4. When the reader thread sees the matching response, it calls future.complete().
     *
     * @param msg the message to send (must have a unique requestId)
     * @return a future that will complete with the server's response
     */
    public CompletableFuture<Message> send(Message msg) {
        CompletableFuture<Message> future = new CompletableFuture<>();
        pending.put(msg.getRequestId(), future); // register before sending to avoid race
        String json = gson.toJson(msg);
        synchronized (this) { out.println(json); } // synchronized: FX thread may also call send()
        return future;
    }

    /**
     * Blocking convenience wrapper — sends and waits for the response.
     * NOT for use on the FX thread (would freeze the UI).
     * Used in tests and background setup tasks.
     *
     * @param timeoutMs maximum milliseconds to wait before TimeoutException
     */
    public Message sendSync(Message msg, long timeoutMs) throws Exception {
        return send(msg).get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Returns the shared Gson instance (used by controllers to parse responses). */
    public Gson getGson() { return gson; }

    // ── Background reader loop ─────────────────────────────────────────────────

    /**
     * Runs on the server-reader thread.
     * Reads one JSON line per loop iteration, deserialises it into a Message,
     * and routes it to either a pending CompletableFuture or the BroadcastListener.
     *
     * On IOException (server closed / network error):
     *   - Completes all pending futures exceptionally so controllers don't hang.
     *   - The reader thread exits cleanly.
     */
    private void readLoop() {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream()))) {
            String line;
            while ((line = in.readLine()) != null) { // null = server closed the connection
                Message msg = gson.fromJson(line, Message.class);
                route(msg);
            }
        } catch (IOException e) {
            log.warn("Server connection lost: {}", e.getMessage());
            // Fail all waiting futures so their callbacks run with an exception
            // rather than hanging forever.
            pending.values().forEach(f ->
                    f.completeExceptionally(new IOException("Connection lost")));
            pending.clear();
        }
    }

    /**
     * Route an incoming message to the correct destination.
     *
     * If the requestId matches a pending future → it's a response; complete the future.
     * If no pending future exists → it's a server-push broadcast; dispatch it.
     */
    private void route(Message msg) {
        CompletableFuture<Message> future = pending.remove(msg.getRequestId());
        if (future != null) {
            future.complete(msg); // wake up the waiting whenCompleteAsync callback
        } else {
            dispatchBroadcast(msg); // no future waiting — must be a broadcast
        }
    }

    /**
     * Dispatch a server-push broadcast to the registered BroadcastListener.
     *
     * Platform.runLater() schedules the callback on the JavaFX Application Thread
     * because all UI updates (labels, charts, tables) must happen there.
     * This method runs on the server-reader thread; the actual UI work runs later.
     */
    private void dispatchBroadcast(Message msg) {
        BroadcastListener listener = this.broadcastListener; // read volatile once
        if (listener == null) return; // no detail screen open — ignore the broadcast

        Platform.runLater(() -> { // schedule on FX thread
            try {
                switch (msg.getType()) {
                    case BID_BROADCAST ->
                            listener.onBidBroadcast(msg.parsePayload(gson, BidResponse.class));
                    case AUCTION_END_BROADCAST ->
                            listener.onAuctionEnded(msg.parsePayload(gson, AuctionDTO.class));
                    case AUCTION_EXTENDED ->
                            listener.onAuctionExtended(
                                    msg.parsePayload(gson, AuctionExtendedNotice.class));
                    default ->
                            log.debug("Unhandled broadcast type: {}", msg.getType());
                }
            } catch (Exception e) {
                log.warn("Failed to dispatch broadcast {}: {}", msg.getType(), e.getMessage());
            }
        });
    }
}
