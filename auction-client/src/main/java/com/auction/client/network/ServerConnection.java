package com.auction.client.network;

import com.auction.common.protocol.Message;
import com.auction.common.protocol.MessageType;
import com.auction.common.request.Responses.AuctionExtendedNotice;
import com.auction.common.request.Responses.BidResponse;
import com.auction.common.request.Responses.ErrorResponse;
import com.auction.common.dto.AuctionDTO;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javafx.application.Platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;

import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * Wraps the client’s single TCP socket: sends requests and sorts incoming lines into
 * “reply for a pending request” vs “live broadcast”.
 *
 * <p><b>Runtime flow:</b> ClientMain creates one instance at startup and stores it in
 * ClientSession; controllers call send(), sendOnFxThread(), or setBroadcastListener() until
 * the application exits and disconnect() closes the socket.</p>
 *
 * <p><b>Threading model:</b> JavaFX must touch UI controls only on the FX thread. Network
 * reads run on a background executor ({@code readLoop}). {@link CompletableFuture} completes
 * on that reader thread, so controllers use {@link #sendOnFxThread(Message, BiConsumer)}
 * before they update labels and tables.</p>
 *
 * <p><b>Generics:</b> {@code ConcurrentHashMap<String, CompletableFuture<Message>>} maps each
 * outgoing {@code requestId} to the future that should complete when the matching line arrives.</p>
 *
 * <p><b>Observer-style UI hook:</b> {@link BroadcastListener} is an inner interface — the
 * auction detail controller implements it and registers with {@link #setBroadcastListener}
 * while the screen is open.</p>
 */
public final class ServerConnection {

    private static final Logger log = LoggerFactory.getLogger(ServerConnection.class);

    /**
     * Optional callback the UI registers while viewing a live auction.
     * Methods always run on the JavaFX application thread via {@link Platform#runLater}.
     */
    public interface BroadcastListener {
        /** Called on the FX thread when a new bid is placed. */
        void onBidBroadcast(BidResponse bidResponse);

        /** Called on the FX thread when the auction ends. */
        void onAuctionEnded(AuctionDTO auction);

        /** Called on the FX thread when anti-sniping extends the auction. */
        void onAuctionExtended(AuctionExtendedNotice notice);
    }

    // Reused for all JSON conversion.
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    // Socket is the client's end of the TCP connection.
    // TCP is a steady two-way conversation, so the app can both send requests and receive live broadcasts.
    private Socket socket;
    private PrintWriter out;

    // Requests waiting for their matching server response.
    // ConcurrentHashMap is used because the UI thread adds requests while the reader thread completes them.
    private final ConcurrentHashMap<String, CompletableFuture<Message>> pending =
            new ConcurrentHashMap<>();

    // volatile means the reader thread sees the latest listener set by the JavaFX UI thread.
    private volatile BroadcastListener broadcastListener;

    // ExecutorService owns the background reader thread.
    // The JavaFX UI thread must stay free to repaint the window and handle button clicks.
    private final ExecutorService readerThread =
            Executors.newSingleThreadExecutor(r -> {
                // This thread blocks in readLoop(), waiting for server messages.
                Thread t = new Thread(r, "server-reader");
                t.setDaemon(true);
                return t;
            });

    // Connection management

    /**
     * Open the TCP connection and start the background reader.
     *
     * @param host the server hostname or IP address (default "localhost")
     * @param port the server TCP port (default 9090)
     * @throws IOException if the connection cannot be established
     */
    public void connect(String host, int port) throws IOException {
        // new Socket(host, port) opens the network connection to the server's ServerSocket.
        // If the server is not running, this constructor throws instead of giving a half-connected object.
        socket = new Socket(host, port);
        // PrintWriter writes text to the socket. The final true means each println is sent immediately.
        out    = new PrintWriter(
                new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
        // submit starts readLoop on the background reader thread, not on the JavaFX UI thread.
        readerThread.submit(this::readLoop);
        log.info("Connected to {}:{}", host, port);
    }

    /** Close the socket and let the reader thread stop. */
    public void disconnect() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    /** True if the socket is open and connected. */
    public boolean isConnected() {
        return socket != null && !socket.isClosed();
    }

    /**
     * Set or clear the listener for live auction updates.
     */
    public void setBroadcastListener(BroadcastListener listener) {
        this.broadcastListener = listener;
    }

    // Request-response API

    /**
     * Send a message and return a future for the matching response.
     */
    public CompletableFuture<Message> send(Message msg) {
        // CompletableFuture is a promise for a value that will arrive later.
        // Here, the value arrives when readLoop sees a response with the same requestId.
        CompletableFuture<Message> future = new CompletableFuture<>();
        pending.put(msg.getRequestId(), future);
        String json = gson.toJson(msg);
        // synchronized prevents two UI actions from writing two JSON lines at the same exact time.
        synchronized (this) { out.println(json); }
        return future;
    }

    /**
     * Send a request and run the completion callback on the JavaFX application thread.
     *
     * <p>JavaFX controls are not thread-safe. Controllers use this helper for async server calls
     * so labels, buttons, and table contents are updated only after {@link Platform#runLater(Runnable)}
     * has moved execution back to the UI thread.</p>
     */
    public void sendOnFxThread(Message msg, BiConsumer<Message, Throwable> completion) {
        send(msg).whenCompleteAsync((response, error) ->
                // Platform.runLater queues the callback onto the JavaFX Application Thread.
                // That is the only thread allowed to change labels, tables, buttons, and charts.
                Platform.runLater(() -> completion.accept(response, error)));
    }

    /**
     * Parse the shared ERROR payload format with this connection's Gson instance.
     */
    public String errorMessage(Message msg) {
        return msg.parsePayload(gson, ErrorResponse.class).message;
    }

    /**
     * Blocking wrapper for tests or background work.
     * Do not call this on the FX thread.
     */
    public Message sendSync(Message msg, long timeoutMs) throws Exception {
        return send(msg).get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Returns the shared Gson instance (used by controllers to parse responses). */
    public Gson getGson() { return gson; }

    // Background reader loop

    /**
     * Reads server messages and routes them to a request future or broadcast listener.
     */
    private void readLoop() {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream()))) {
            String line;
            // readLine() blocks until the server sends one newline-terminated JSON message.
            // null means the server closed the connection.
            while ((line = in.readLine()) != null) {
                Message msg = gson.fromJson(line, Message.class);
                route(msg);
            }
        } catch (IOException e) {
            log.warn("Server connection lost: {}", e.getMessage());
            // Fail waiting requests instead of leaving their callbacks pending.
            pending.values().forEach(f ->
                    f.completeExceptionally(new IOException("Connection lost")));
            pending.clear();
        }
    }

    /**
     * Send responses to their waiting future; treat everything else as a broadcast.
     */
    private void route(Message msg) {
        // The requestId tells us whether this incoming line answers a request we sent earlier.
        CompletableFuture<Message> future = pending.remove(msg.getRequestId());
        if (future != null) {
            future.complete(msg);
        } else {
            dispatchBroadcast(msg);
        }
    }

    /**
     * Deliver a broadcast to the UI listener on the JavaFX thread.
     */
    private void dispatchBroadcast(Message msg) {
        BroadcastListener listener = this.broadcastListener;
        if (listener == null) return;

        // Broadcasts also arrive on the reader thread, so UI callbacks must be moved to the JavaFX thread.
        Platform.runLater(() -> {
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
