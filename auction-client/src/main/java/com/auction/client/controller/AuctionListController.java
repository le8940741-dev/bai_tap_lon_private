package com.auction.client.controller;

/**
 * FILE ROLE:
FILE ROLE: Controller for the main auction list screen (auction_list.fxml).

Implements Refreshable so SceneManager calls refresh() every time this screen
is navigated to, ensuring the list is always current.

KEY BEHAVIOURS:
  - Loads all auctions via GET_AUCTIONS on refresh().
  - Applies a real-time text filter (FilteredList) so the user can search by
    item name or status without another server request.
  - Double-clicking a row opens the auction detail screen for that auction.
  - The TableView uses SimpleStringProperty cell factories to display fields
    from AuctionDTO (id, item name, current price, status, end time).

IMPORT NOTES:
  - ObservableList: JavaFX's list type that notifies the TableView of changes.
  - FilteredList: wraps the ObservableList to apply a predicate without copying.
  - SimpleStringProperty: wraps a String so TableColumn cell factories can bind to it.
  - AuctionsResponse: the server's response payload containing the auction list.
 */

import com.auction.client.network.ServerConnection;
import com.auction.client.session.ClientSession;
import com.auction.client.util.AlertUtil;
import com.auction.client.util.SceneManager;
import com.auction.common.dto.AuctionDTO;
import com.auction.common.protocol.Message;
import com.auction.common.protocol.MessageType;
import com.auction.common.request.Responses.AuctionsResponse;
import com.auction.common.request.Responses.ErrorResponse;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.util.List;

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
                new SimpleStringProperty(formatDateTime(c.getValue().getEndTime())));

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

        // Double-click to view detail
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

        conn.send(msg).whenCompleteAsync((response, ex) -> Platform.runLater(() -> {
            if (ex != null) { AlertUtil.error("Error", ex.getMessage()); return; }
            if (response.getType() == MessageType.ERROR) {
                AlertUtil.error("Error", response.parsePayload(conn.getGson(), ErrorResponse.class).message);
                return;
            }
            AuctionsResponse resp = response.parsePayload(conn.getGson(), AuctionsResponse.class);
            allAuctions.setAll(resp.auctions != null ? resp.auctions : List.of());
        }));
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
        ClientSession session = ClientSession.getInstance();
        ServerConnection conn = session.getConnection();
        Message msg = Message.of(MessageType.LOGOUT, com.auction.common.request.EmptyPayload.INSTANCE, conn.getGson());
        conn.send(msg);
        session.logout();
        SceneManager.evictAll();
        SceneManager.switchTo(SceneManager.View.LOGIN);
    }

    private String formatDateTime(String iso) {
        if (iso == null) return "";
        return iso.replace("T", " ").substring(0, Math.min(16, iso.length()));
    }
}
