package com.auction.client.controller;

/**
 * FXML controller for {@code auction_list.fxml} — the bidder’s home screen.
 *
 * <p>Implements {@link com.auction.client.util.SceneManager.Refreshable} so {@link com.auction.client.util.SceneManager}
 * can reload data whenever the user navigates back. Uses {@link javafx.collections.transformation.FilteredList}
 * (decorator pattern around an {@link javafx.collections.ObservableList}) for live search without re-querying the server.</p>
 */
import com.auction.client.network.ServerConnection;
import com.auction.client.session.ClientSession;
import com.auction.client.util.AlertUtil;
import com.auction.client.util.DisplayFormat;
import com.auction.client.util.SceneManager;
import com.auction.client.util.SessionActions;
import com.auction.common.dto.AuctionDTO;
import com.auction.common.protocol.Message;
import com.auction.common.protocol.MessageType;
import com.auction.common.request.Responses.AuctionsResponse;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.util.List;

/**
 * Loads and filters auction rows, then delegates selected-auction navigation to SceneManager.
 *
 * <p>SceneManager creates this controller when auction_list.fxml is first shown; JavaFX calls
 * initialize(), and SceneManager calls refresh() whenever the cached view returns to the screen.</p>
 */
public final class AuctionListController implements SceneManager.Refreshable {

    @FXML private TableView<AuctionDTO>              auctionTable;
    @FXML private TableColumn<AuctionDTO, String>    colId;
    @FXML private TableColumn<AuctionDTO, String>    colItem;
    @FXML private TableColumn<AuctionDTO, String>    colPrice;
    @FXML private TableColumn<AuctionDTO, String>    colStatus;
    @FXML private TableColumn<AuctionDTO, String>    colEnds;
    @FXML private TextField                          searchField;
    @FXML private Label                              userLabel;
    @FXML private Button                             logoutButton;

    private final ObservableList<AuctionDTO> allAuctions = FXCollections.observableArrayList();
    private FilteredList<AuctionDTO> filteredAuctions;

    @FXML
    private void initialize() {
        colId.setCellValueFactory(c ->
                new SimpleStringProperty(String.valueOf(c.getValue().getId())));
        colItem.setCellValueFactory(c -> {
            AuctionDTO a = c.getValue();
            String name = a.getItem() != null ? a.getItem().getName() : "(unknown)";
            return new SimpleStringProperty(name);
        });
        colPrice.setCellValueFactory(c ->
                new SimpleStringProperty(String.format("$%.2f", c.getValue().getCurrentPrice())));
        colStatus.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getStatus()));
        colEnds.setCellValueFactory(c ->
                new SimpleStringProperty(DisplayFormat.isoToMinuteLabel(c.getValue().getEndTime())));

        // FilteredList: JavaFX decorator around ObservableList used here so search is local and instant.
        filteredAuctions = new FilteredList<>(allAuctions, a -> true);
        auctionTable.setItems(filteredAuctions);

        searchField.textProperty().addListener((obs, old, val) -> {
            String lower = val.toLowerCase();
            filteredAuctions.setPredicate(a -> {
                if (lower.isEmpty()) return true;
                String itemName = a.getItem() != null ? a.getItem().getName().toLowerCase() : "";
                return itemName.contains(lower) || a.getStatus().toLowerCase().contains(lower);
            });
        });

        // Double-click a row to open its detail screen.
        auctionTable.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                AuctionDTO selected = auctionTable.getSelectionModel().getSelectedItem();
                if (selected != null) SceneManager.showAuctionDetail(selected.getId());
            }
        });
    }

    @Override
    public void refresh() {
        userLabel.setText("Logged in as: " + ClientSession.getInstance().getCurrentUser().getUsername());
        loadAuctions();
    }

    private void loadAuctions() {
        ServerConnection conn = ClientSession.getInstance().getConnection();
        Message msg = Message.of(MessageType.GET_AUCTIONS, com.auction.common.request.EmptyPayload.INSTANCE, conn.getGson());

        conn.sendOnFxThread(msg, (response, ex) -> {
            if (ex != null) { AlertUtil.error("Error", ex.getMessage()); return; }
            if (response.getType() == MessageType.ERROR) {
                AlertUtil.error("Error", conn.errorMessage(response));
                return;
            }
            AuctionsResponse resp = response.parsePayload(conn.getGson(), AuctionsResponse.class);
            allAuctions.setAll(resp.auctions != null ? resp.auctions : List.of());
        });
    }

    @FXML
    private void onRefresh() { loadAuctions(); }

    @FXML
    private void onViewDetail() {
        AuctionDTO selected = auctionTable.getSelectionModel().getSelectedItem();
        if (selected == null) { AlertUtil.info("Select Auction", "Please select an auction first."); return; }
        SceneManager.showAuctionDetail(selected.getId());
    }

    @FXML
    private void onLogout() {
        SessionActions.logoutToLogin();
    }

}
