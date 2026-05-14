package com.auction.client.controller;

import com.auction.client.network.ServerConnection;
import com.auction.client.network.ServerConnection.BroadcastListener;
import com.auction.client.session.ClientSession;
import com.auction.client.util.AlertUtil;
import com.auction.client.util.DisplayFormat;
import com.auction.client.util.ImageSourceResolver;
import com.auction.client.util.SceneManager;
import com.auction.common.dto.AuctionDTO;
import com.auction.common.dto.BidDTO;
import com.auction.common.protocol.Message;
import com.auction.common.protocol.MessageType;
import com.auction.common.request.Requests.GetBidHistoryRequest;
import com.auction.common.request.Requests.GetAuctionDetailRequest;
import com.auction.common.request.Requests.PlaceBidRequest;
import com.auction.common.request.Requests.SetAutoBidRequest;
import com.auction.common.request.Requests.WatchAuctionRequest;
import com.auction.common.request.Responses.AuctionExtendedNotice;
import com.auction.common.request.Responses.BidHistoryResponse;
import com.auction.common.request.Responses.BidResponse;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.text.Text;
import javafx.util.StringConverter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * FXML controller for {@code auction_detail.fxml} — the richest client screen.
 *
 * <p><b>Dual communication paths:</b> (1) normal request/response for detail, bids, placing bids.
 * (2) Implements {@link com.auction.client.network.ServerConnection.BroadcastListener} so the
 * {@link com.auction.client.network.ServerConnection} reader thread can push competitor bids,
 * auction end, and anti-snipe extensions while this view is open.</p>
 *
 * <p><b>JavaFX charts and generics:</b> {@link javafx.scene.chart.LineChart} is parameterized as
 * {@code LineChart<Number, Number>} with an {@link javafx.collections.ObservableList} of
 * {@link com.auction.common.dto.BidDTO} rows in the table — study how model objects map to UI cells.</p>
 */
/**
 * Owns the active auction detail view and bridges request/response data with live broadcast callbacks.
 *
 * <p>SceneManager creates a fresh instance for each selected auction, then loadAuction() subscribes
 * this controller to bid, auction-end, and anti-snipe updates until onBack() unsubscribes.</p>
 */
public final class AuctionDetailController implements BroadcastListener {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    @FXML private Text labelTitle;
    @FXML private ImageView itemImageView;
    @FXML private Label itemImagePlaceholder;
    @FXML private Label labelCategory;
    @FXML private Label labelDescription;
    @FXML private Label labelSeller;
    @FXML private Label labelCurrentPrice;
    @FXML private Label labelLeader;
    @FXML private Label labelStatus;
    @FXML private Label labelEndTime;
    @FXML private Label labelCountdown;

    @FXML private TextField bidAmountField;
    @FXML private Button bidButton;
    @FXML private TextField autoBidMaxField;
    @FXML private TextField autoBidIncrField;
    @FXML private Button autoBidButton;
    @FXML private Label bidStatusLabel;

    @FXML private TableView<BidDTO> bidTable;
    @FXML private TableColumn<BidDTO, String> colBidder;
    @FXML private TableColumn<BidDTO, String> colAmount;
    @FXML private TableColumn<BidDTO, String> colTime;
    @FXML private TableColumn<BidDTO, String> colAuto;

    @FXML private LineChart<Number, Number> priceChart;
    @FXML private NumberAxis xAxis;
    @FXML private NumberAxis yAxis;

    private final ObservableList<BidDTO> bidList = FXCollections.observableArrayList();

    private XYChart.Series<Number, Number> priceSeries;
    private long currentAuctionId;
    // Timer runs small repeated work on its own background thread.
    // The countdown uses it so the UI can keep responding while time is recalculated once per second.
    private Timer countdownTimer;
    private long endTimeEpochSec;

    @FXML
    private void initialize() {
        colBidder.setCellValueFactory(cell ->
                new javafx.beans.property.SimpleStringProperty(cell.getValue().getBidderName()));
        colAmount.setCellValueFactory(cell ->
                new javafx.beans.property.SimpleStringProperty(
                        String.format("$%.2f", cell.getValue().getAmount())));
        colTime.setCellValueFactory(cell ->
                new javafx.beans.property.SimpleStringProperty(
                        formatMs(cell.getValue().getTimestampMillis())));
        colAuto.setCellValueFactory(cell ->
                new javafx.beans.property.SimpleStringProperty(
                        cell.getValue().isAutoBid() ? "AUTO" : ""));
        bidTable.setItems(bidList);

        priceSeries = new XYChart.Series<>();
        priceSeries.setName("Price");
        priceChart.getData().add(priceSeries);
        priceChart.setAnimated(false);
        xAxis.setLabel("Time");
        xAxis.setForceZeroInRange(false);
        xAxis.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number value) {
                return value == null ? "" : formatEpochSeconds(value.longValue());
            }

            @Override
            public Number fromString(String string) {
                throw new UnsupportedOperationException("Chart axis does not parse text labels");
            }
        });
        yAxis.setForceZeroInRange(false);
        yAxis.setLabel("Price ($)");

        showBidControls(ClientSession.getInstance().isBidder());
    }

    private void showBidControls(boolean canBid) {
        bidAmountField.setVisible(canBid);
        bidButton.setVisible(canBid);
        autoBidMaxField.setVisible(canBid);
        autoBidIncrField.setVisible(canBid);
        autoBidButton.setVisible(canBid);
    }

    public void loadAuction(long auctionId) {
        currentAuctionId = auctionId;

        ServerConnection conn = ClientSession.getInstance().getConnection();
        conn.setBroadcastListener(this);

        // WATCH_AUCTION tells the server to push future bid/end/extension messages for this auction.
        // This is different from asking once for details; it keeps the server sending live updates while this screen is open.
        Message watchMsg = Message.of(
                MessageType.WATCH_AUCTION,
                new WatchAuctionRequest(auctionId),
                conn.getGson());
        conn.send(watchMsg);

        loadAuctionDetail(auctionId);
        loadBidHistory(auctionId);
    }

    private void loadAuctionDetail(long auctionId) {
        ServerConnection conn = ClientSession.getInstance().getConnection();
        Message detailMsg = Message.of(
                MessageType.GET_AUCTION_DETAIL,
                new GetAuctionDetailRequest(auctionId),
                conn.getGson());

        conn.sendOnFxThread(detailMsg, (resp, ex) -> {
            if (ex != null) {
                AlertUtil.error("Error", ex.getMessage());
                return;
            }
            if (resp.getType() == MessageType.ERROR) {
                AlertUtil.error("Error", conn.errorMessage(resp));
                return;
            }
            populateDetails(resp.parsePayload(conn.getGson(), AuctionDTO.class));
        });
    }

    private void populateDetails(AuctionDTO auction) {
        labelTitle.setText(auction.getItem() != null ? auction.getItem().getName() : "N/A");
        showItemImage(auction.getItem() != null ? auction.getItem().getImageUrl() : null);
        labelCategory.setText(auction.getItem() != null ? auction.getItem().getCategory() : "");
        labelDescription.setText(
                auction.getItem() != null ? auction.getItem().getDescription() : "");
        labelSeller.setText(auction.getSellerName());
        labelCurrentPrice.setText(String.format("$%.2f", auction.getCurrentPrice()));
        labelLeader.setText(
                auction.getWinnerName() != null ? auction.getWinnerName() : "No bids yet");
        labelStatus.setText(auction.getStatus());
        labelEndTime.setText(DisplayFormat.isoToMinuteLabel(auction.getEndTime()));

        try {
            LocalDateTime end = LocalDateTime.parse(auction.getEndTime());
            endTimeEpochSec = end.atZone(ZoneId.systemDefault()).toEpochSecond();
            startCountdown();
        } catch (Exception ignored) {
            stopCountdown();
            labelCountdown.setText("");
        }
    }

    private void loadBidHistory(long auctionId) {
        ServerConnection conn = ClientSession.getInstance().getConnection();
        Message msg = Message.of(
                MessageType.GET_BID_HISTORY,
                new GetBidHistoryRequest(auctionId),
                conn.getGson());

        conn.sendOnFxThread(msg, (resp, ex) -> {
            if (ex != null) {
                bidStatusLabel.setText("Error: " + ex.getMessage());
                return;
            }
            if (resp.getType() == MessageType.ERROR) {
                bidStatusLabel.setText(conn.errorMessage(resp));
                return;
            }
            BidHistoryResponse history = resp.parsePayload(conn.getGson(), BidHistoryResponse.class);
            List<BidDTO> bids = history.bids != null ? history.bids : List.of();
            bidList.setAll(bids);
            rebuildChart(bids);
        });
    }

    @Override
    public void onBidBroadcast(BidResponse response) {
        if (response.auction == null || response.auction.getId() != currentAuctionId) {
            return;
        }

        labelCurrentPrice.setText(String.format("$%.2f", response.auction.getCurrentPrice()));
        labelLeader.setText(
                response.auction.getWinnerName() != null ? response.auction.getWinnerName() : "");
        labelStatus.setText(response.auction.getStatus());
        bidStatusLabel.setText("New bid: $"
                + String.format("%.2f", response.auction.getCurrentPrice())
                + " by "
                + response.auction.getWinnerName());

        if (response.bid != null) {
            bidList.add(response.bid);
            appendChartPoint(response.bid.getTimestampMillis(), response.bid.getAmount());
        }
    }

    @Override
    public void onAuctionEnded(AuctionDTO auction) {
        if (auction.getId() != currentAuctionId) {
            return;
        }
        labelStatus.setText("FINISHED");
        bidStatusLabel.setText("Auction ended. Winner: "
                + (auction.getWinnerName() != null ? auction.getWinnerName() : "none"));
        stopCountdown();
    }

    @Override
    public void onAuctionExtended(AuctionExtendedNotice notice) {
        if (notice.auctionId != currentAuctionId) {
            return;
        }
        labelEndTime.setText(DisplayFormat.isoToMinuteLabel(notice.newEndTime) + " (extended)");
        try {
            LocalDateTime newEnd = LocalDateTime.parse(notice.newEndTime);
            endTimeEpochSec = newEnd.atZone(ZoneId.systemDefault()).toEpochSecond();
        } catch (Exception ignored) {
            return;
        }
        bidStatusLabel.setText("Auction extended (anti-sniping).");
    }

    @FXML
    private void onPlaceBid() {
        Double amount = parseDoubleField(bidAmountField, "Invalid amount.");
        if (amount == null) {
            return;
        }

        bidButton.setDisable(true);
        ServerConnection conn = ClientSession.getInstance().getConnection();
        Message msg = Message.of(
                MessageType.PLACE_BID,
                new PlaceBidRequest(currentAuctionId, amount),
                conn.getGson());

        conn.sendOnFxThread(msg, (resp, ex) -> {
            bidButton.setDisable(false);
            if (ex != null) {
                bidStatusLabel.setText("Error: " + ex.getMessage());
                return;
            }
            if (resp.getType() == MessageType.ERROR) {
                bidStatusLabel.setText(conn.errorMessage(resp));
                return;
            }
            bidAmountField.clear();
            bidStatusLabel.setText("Bid placed!");
        });
    }

    @FXML
    private void onSetAutoBid() {
        Double maxBid = parseDoubleField(autoBidMaxField, "Invalid auto-bid values.");
        Double increment = parseDoubleField(autoBidIncrField, "Invalid auto-bid values.");
        if (maxBid == null || increment == null) {
            return;
        }

        autoBidButton.setDisable(true);
        ServerConnection conn = ClientSession.getInstance().getConnection();
        Message msg = Message.of(
                MessageType.SET_AUTO_BID,
                new SetAutoBidRequest(currentAuctionId, maxBid, increment),
                conn.getGson());

        conn.sendOnFxThread(msg, (resp, ex) -> {
            autoBidButton.setDisable(false);
            if (ex != null) {
                bidStatusLabel.setText("Error: " + ex.getMessage());
                return;
            }
            if (resp.getType() == MessageType.ERROR) {
                bidStatusLabel.setText(conn.errorMessage(resp));
                return;
            }
            bidStatusLabel.setText("Auto-bid saved.");
            loadAuctionDetail(currentAuctionId);
            loadBidHistory(currentAuctionId);
        });
    }

    @FXML
    private void onBack() {
        stopCountdown();

        ServerConnection conn = ClientSession.getInstance().getConnection();
        conn.setBroadcastListener(null);
        Message unwatch = Message.of(
                MessageType.UNWATCH_AUCTION,
                new WatchAuctionRequest(currentAuctionId),
                conn.getGson());
        conn.send(unwatch);

        SceneManager.switchTo(ClientSession.getInstance().isSeller()
                ? SceneManager.View.SELLER_DASHBOARD
                : SceneManager.View.AUCTION_LIST);
    }

    private void rebuildChart(List<BidDTO> bids) {
        priceSeries.getData().clear();
        bids.forEach(bid -> appendChartPoint(bid.getTimestampMillis(), bid.getAmount()));
    }

    private void appendChartPoint(long epochMs, double price) {
        priceSeries.getData().add(new XYChart.Data<>(epochMs / 1000.0, price));
    }

    private Double parseDoubleField(TextField field, String errorMessage) {
        try {
            return Double.parseDouble(field.getText().trim());
        } catch (NumberFormatException e) {
            bidStatusLabel.setText(errorMessage);
            return null;
        }
    }

    private void showItemImage(String imageSource) {
        String normalized = ImageSourceResolver.toImageSource(imageSource);
        if (normalized == null) {
            setImagePlaceholder("No product image.");
            return;
        }

        Image image = new Image(normalized, false);
        if (image.isError()) {
            setImagePlaceholder("Unable to load product image.");
            return;
        }

        itemImageView.setImage(image);
        itemImageView.setManaged(true);
        itemImageView.setVisible(true);
        itemImagePlaceholder.setManaged(false);
        itemImagePlaceholder.setVisible(false);
    }

    private void setImagePlaceholder(String message) {
        itemImageView.setImage(null);
        itemImageView.setManaged(false);
        itemImageView.setVisible(false);
        itemImagePlaceholder.setText(message);
        itemImagePlaceholder.setManaged(true);
        itemImagePlaceholder.setVisible(true);
    }

    private void startCountdown() {
        stopCountdown();
        // Timer is a lightweight JDK scheduler. The true argument makes its thread a daemon,
        // so this countdown thread will not keep the whole app alive after the window closes.
        countdownTimer = new Timer(true);
        // scheduleAtFixedRate runs this TimerTask every 1000 milliseconds.
        // TimerTask.run() does not run on the JavaFX UI thread, so it must not touch labels directly.
        countdownTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                long remaining = endTimeEpochSec - Instant.now().getEpochSecond();
                String text;
                if (remaining <= 0) {
                    text = "Ended";
                    cancel();
                } else {
                    long hours = remaining / 3600;
                    long minutes = (remaining % 3600) / 60;
                    long seconds = remaining % 60;
                    text = String.format("%02d:%02d:%02d", hours, minutes, seconds);
                }

                String finalText = text;
                // Platform.runLater moves the label update onto the JavaFX Application Thread.
                // Without this handoff, the countdown would be changing UI controls from the wrong thread.
                Platform.runLater(() -> labelCountdown.setText(finalText));
            }
        }, 0, 1000);
    }

    private void stopCountdown() {
        if (countdownTimer != null) {
            countdownTimer.cancel();
            countdownTimer = null;
        }
    }

    private String formatMs(long ms) {
        if (ms == 0) {
            return "";
        }
        return Instant.ofEpochMilli(ms)
                .atZone(ZoneId.systemDefault())
                .format(TIME_FORMAT);
    }

    private String formatEpochSeconds(long seconds) {
        if (seconds <= 0) {
            return "";
        }
        return Instant.ofEpochSecond(seconds)
                .atZone(ZoneId.systemDefault())
                .format(TIME_FORMAT);
    }
}
