package com.auction.client.controller;

/**
 * FILE ROLE:
FILE ROLE: Controller for the auction detail screen (auction_detail.fxml).

The most complex controller — manages live bidding, realtime broadcast handling,
auto-bid registration, a countdown timer, and a realtime price chart.

IMPLEMENTS BroadcastListener:
  Registered with ServerConnection when the screen opens.
  Receives onBidBroadcast / onAuctionEnded / onAuctionExtended callbacks on
  the FX thread (Platform.runLater already applied by ServerConnection).
  Updates labels, bid table, and chart data in response to server-push events.

LIFECYCLE:
  1. SceneManager calls loadAuction(id) immediately after construction.
  2. loadAuction() registers as BroadcastListener, sends WATCH_AUCTION,
     loads auction details, and loads bid history (for table + chart rebuild).
  3. onBack() sends UNWATCH_AUCTION and clears the BroadcastListener before
     navigating away, so future broadcasts for this auction are ignored.

COUNTDOWN TIMER:
  A java.util.Timer fires every second to compute remaining time and update
  the labelCountdown text.  Stopped by stopCountdown() on navigation or auction end.
  Timer.scheduleAtFixedRate() is used so the timer stays on schedule even if a
  tick is slightly late.

PRICE CHART:
  JavaFX LineChart with numeric X-axis (epoch seconds) and Y-axis (price).
  Points are appended one-by-one on each broadcast — no full redraw needed.
  Initial history loaded from BID_HISTORY_RESPONSE populates all past points.

IMPORT NOTES:
  - LineChart / NumberAxis / XYChart: JavaFX chart components for the price curve.
  - Timer / TimerTask: standard Java countdown mechanism (not FX Timeline).
  - BidResponse: broadcast payload carrying both the new bid and updated auction.
  - AuctionExtendedNotice: broadcast payload carrying the new end time.
  - Instant / ZoneId / DateTimeFormatter: timestamp conversions for display.
 */

import com.auction.client.network.ServerConnection;
import com.auction.client.network.ServerConnection.BroadcastListener;
import com.auction.client.session.ClientSession;
import com.auction.client.util.AlertUtil;
import com.auction.client.util.SceneManager;
import com.auction.common.dto.AuctionDTO;
import com.auction.common.dto.BidDTO;
import com.auction.common.protocol.Message;
import com.auction.common.protocol.MessageType;
import com.auction.common.request.Requests.*;
import com.auction.common.request.Responses.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public final class AuctionDetailController implements BroadcastListener {

    // ── Info labels ──────────────────────────────────────────────────────────
    @FXML private Label labelTitle;
    @FXML private Label labelCategory;
    @FXML private Label labelDescription;
    @FXML private Label labelSeller;
    @FXML private Label labelCurrentPrice;
    @FXML private Label labelLeader;
    @FXML private Label labelStatus;
    @FXML private Label labelEndTime;
    @FXML private Label labelCountdown;

    // ── Bid controls ─────────────────────────────────────────────────────────
    @FXML private TextField  bidAmountField;
    @FXML private Button     bidButton;
    @FXML private TextField  autoBidMaxField;
    @FXML private TextField  autoBidIncrField;
    @FXML private Button     autoBidButton;
    @FXML private Label      bidStatusLabel;

    // ── Bid history table ─────────────────────────────────────────────────────
    @FXML private TableView<BidDTO>            bidTable;
    @FXML private TableColumn<BidDTO, String>  colBidder;
    @FXML private TableColumn<BidDTO, String>  colAmount;
    @FXML private TableColumn<BidDTO, String>  colTime;
    @FXML private TableColumn<BidDTO, String>  colAuto;

    // ── Price chart ───────────────────────────────────────────────────────────
    @FXML private LineChart<Number, Number> priceChart;
    @FXML private NumberAxis                xAxis;
    @FXML private NumberAxis                yAxis;

    private final ObservableList<BidDTO> bidList = FXCollections.observableArrayList();
    private XYChart.Series<Number, Number> priceSeries;

    private long currentAuctionId;
    private Timer countdownTimer;
    private long endTimeEpochSec;

    // ── Init ─────────────────────────────────────────────────────────────────

    @FXML
    private void initialize() {
        colBidder.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(c.getValue().getBidderName()));
        colAmount.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(
                        String.format("$%.2f", c.getValue().getAmount())));
        colTime.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(
                        formatMs(c.getValue().getTimestampMillis())));
        colAuto.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(
                        c.getValue().isAutoBid() ? "AUTO" : ""));
        bidTable.setItems(bidList);

        // Chart setup
        priceSeries = new XYChart.Series<>();
        priceSeries.setName("Price");
        priceChart.getData().add(priceSeries);
        priceChart.setAnimated(false);
        xAxis.setLabel("Time (epoch s)");
        yAxis.setLabel("Price ($)");

        // Hide bidding controls for non-bidders
        boolean canBid = ClientSession.getInstance().isBidder();
        bidAmountField.setVisible(canBid);
        bidButton.setVisible(canBid);
        autoBidMaxField.setVisible(canBid);
        autoBidIncrField.setVisible(canBid);
        autoBidButton.setVisible(canBid);
    }

    // ── Public entry point called by SceneManager ─────────────────────────────

    public void loadAuction(long auctionId) {
        this.currentAuctionId = auctionId;

        // Register as broadcast listener for this auction
        ServerConnection conn = ClientSession.getInstance().getConnection();
        conn.setBroadcastListener(this);

        // Subscribe for server-push updates
        Message watchMsg = Message.of(MessageType.WATCH_AUCTION,
                new WatchAuctionRequest(auctionId), conn.getGson());
        conn.send(watchMsg);

        // Load full detail
        Message detailMsg = Message.of(MessageType.GET_AUCTION_DETAIL,
                new GetAuctionDetailRequest(auctionId), conn.getGson());
        conn.send(detailMsg).whenCompleteAsync((resp, ex) -> Platform.runLater(() -> {
            if (ex != null) { AlertUtil.error("Error", ex.getMessage()); return; }
            if (resp.getType() == MessageType.ERROR) return;
            AuctionDTO auction = resp.parsePayload(conn.getGson(), AuctionDTO.class);
            populateDetails(auction);
        }));

        // Load bid history
        loadBidHistory(auctionId);
    }

    private void populateDetails(AuctionDTO a) {
        labelTitle.setText(a.getItem() != null ? a.getItem().getName() : "N/A");
        labelCategory.setText(a.getItem() != null ? a.getItem().getCategory() : "");
        labelDescription.setText(a.getItem() != null ? a.getItem().getDescription() : "");
        labelSeller.setText(a.getSellerName());
        labelCurrentPrice.setText(String.format("$%.2f", a.getCurrentPrice()));
        labelLeader.setText(a.getWinnerName() != null ? a.getWinnerName() : "No bids yet");
        labelStatus.setText(a.getStatus());
        labelEndTime.setText(formatIso(a.getEndTime()));

        // Start countdown
        try {
            java.time.LocalDateTime end = java.time.LocalDateTime.parse(a.getEndTime());
            endTimeEpochSec = end.atZone(ZoneId.systemDefault()).toEpochSecond();
            startCountdown();
        } catch (Exception ignored) {}
    }

    private void loadBidHistory(long auctionId) {
        ServerConnection conn = ClientSession.getInstance().getConnection();
        Message msg = Message.of(MessageType.GET_BID_HISTORY,
                new GetBidHistoryRequest(auctionId), conn.getGson());

        conn.send(msg).whenCompleteAsync((resp, ex) -> Platform.runLater(() -> {
            if (ex != null || resp.getType() == MessageType.ERROR) return;
            BidHistoryResponse history = resp.parsePayload(conn.getGson(), BidHistoryResponse.class);
            if (history.bids != null) {
                bidList.setAll(history.bids);
                rebuildChart(history.bids);
            }
        }));
    }

    // ── BroadcastListener (called on FX thread by ServerConnection) ───────────

    @Override
    public void onBidPlaced(BidResponse response) {
        if (response.auction == null ||
                response.auction.getId() != currentAuctionId) return;

        // Update price / leader labels
        labelCurrentPrice.setText(String.format("$%.2f", response.auction.getCurrentPrice()));
        labelLeader.setText(response.auction.getWinnerName() != null
                ? response.auction.getWinnerName() : "");
        bidStatusLabel.setText("New bid: $" + String.format("%.2f", response.auction.getCurrentPrice())
                + " by " + response.auction.getWinnerName());

        // Append to table and chart
        if (response.bid != null) {
            bidList.add(response.bid);
            appendChartPoint(response.bid.getTimestampMillis(), response.bid.getAmount());
        }
    }

    @Override
    public void onAuctionEnded(AuctionDTO auction) {
        if (auction.getId() != currentAuctionId) return;
        labelStatus.setText("FINISHED");
        bidStatusLabel.setText("Auction ended. Winner: " +
                (auction.getWinnerName() != null ? auction.getWinnerName() : "none"));
        stopCountdown();
    }

    @Override
    public void onAuctionExtended(AuctionExtendedNotice notice) {
        if (notice.auctionId != currentAuctionId) return;
        labelEndTime.setText(formatIso(notice.newEndTime) + " (extended)");
        try {
            java.time.LocalDateTime newEnd = java.time.LocalDateTime.parse(notice.newEndTime);
            endTimeEpochSec = newEnd.atZone(ZoneId.systemDefault()).toEpochSecond();
        } catch (Exception ignored) {}
        bidStatusLabel.setText("⏱ Auction extended (anti-sniping)!");
    }

    // ── Bid actions ───────────────────────────────────────────────────────────

    @FXML
    private void onPlaceBid() {
        String text = bidAmountField.getText().trim();
        double amount;
        try { amount = Double.parseDouble(text); }
        catch (NumberFormatException e) { bidStatusLabel.setText("Invalid amount."); return; }

        bidButton.setDisable(true);
        ServerConnection conn = ClientSession.getInstance().getConnection();
        Message msg = Message.of(MessageType.PLACE_BID,
                new PlaceBidRequest(currentAuctionId, amount), conn.getGson());

        conn.send(msg).whenCompleteAsync((resp, ex) -> Platform.runLater(() -> {
            bidButton.setDisable(false);
            if (ex != null) { bidStatusLabel.setText("Error: " + ex.getMessage()); return; }
            if (resp.getType() == MessageType.ERROR) {
                bidStatusLabel.setText(resp.parsePayload(conn.getGson(),
                        ErrorResponse.class).message);
                return;
            }
            bidAmountField.clear();
            bidStatusLabel.setText("Bid placed!");
        }));
    }

    @FXML
    private void onSetAutoBid() {
        String maxText  = autoBidMaxField.getText().trim();
        String incrText = autoBidIncrField.getText().trim();
        double maxBid, increment;
        try {
            maxBid    = Double.parseDouble(maxText);
            increment = Double.parseDouble(incrText);
        } catch (NumberFormatException e) {
            bidStatusLabel.setText("Invalid auto-bid values."); return;
        }

        autoBidButton.setDisable(true);
        ServerConnection conn = ClientSession.getInstance().getConnection();
        Message msg = Message.of(MessageType.SET_AUTO_BID,
                new SetAutoBidRequest(currentAuctionId, maxBid, increment), conn.getGson());

        conn.send(msg).whenCompleteAsync((resp, ex) -> Platform.runLater(() -> {
            autoBidButton.setDisable(false);
            if (ex != null) { bidStatusLabel.setText("Error: " + ex.getMessage()); return; }
            if (resp.getType() == MessageType.ERROR) {
                bidStatusLabel.setText(resp.parsePayload(conn.getGson(),
                        ErrorResponse.class).message);
                return;
            }
            bidStatusLabel.setText("Auto-bid registered (max $" + maxBid + ")");
        }));
    }

    @FXML
    private void onBack() {
        stopCountdown();
        // Unwatch
        ServerConnection conn = ClientSession.getInstance().getConnection();
        conn.setBroadcastListener(null);
        Message uw = Message.of(MessageType.UNWATCH_AUCTION,
                new WatchAuctionRequest(currentAuctionId), conn.getGson());
        conn.send(uw);

        boolean isSeller = ClientSession.getInstance().isSeller();
        SceneManager.switchTo(isSeller
                ? SceneManager.View.SELLER_DASHBOARD
                : SceneManager.View.AUCTION_LIST);
    }

    // ── Chart helpers ─────────────────────────────────────────────────────────

    private void rebuildChart(List<BidDTO> bids) {
        priceSeries.getData().clear();
        bids.forEach(b -> appendChartPoint(b.getTimestampMillis(), b.getAmount()));
    }

    private void appendChartPoint(long epochMs, double price) {
        priceSeries.getData().add(
                new XYChart.Data<>(epochMs / 1000.0, price));
    }

    // ── Countdown ─────────────────────────────────────────────────────────────

    private void startCountdown() {
        stopCountdown();
        countdownTimer = new Timer(true);
        countdownTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                long remaining = endTimeEpochSec - Instant.now().getEpochSecond();
                String text;
                if (remaining <= 0) {
                    text = "Ended";
                    cancel();
                } else {
                    long h = remaining / 3600;
                    long m = (remaining % 3600) / 60;
                    long s = remaining % 60;
                    text = String.format("%02d:%02d:%02d", h, m, s);
                }
                String finalText = text;
                Platform.runLater(() -> labelCountdown.setText(finalText));
            }
        }, 0, 1000);
    }

    private void stopCountdown() {
        if (countdownTimer != null) { countdownTimer.cancel(); countdownTimer = null; }
    }

    // ── Format helpers ────────────────────────────────────────────────────────

    private String formatIso(String iso) {
        if (iso == null) return "";
        return iso.replace("T", " ").substring(0, Math.min(16, iso.length()));
    }

    private String formatMs(long ms) {
        if (ms == 0) return "";
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    @Override
    public void onBidBroadcast(BidResponse bidResponse) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'onBidBroadcast'");
    }
}
