package com.auction.client.controller;

/**
 * FILE ROLE:
FILE ROLE: Controller for the seller dashboard screen (seller_dashboard.fxml).

Two panels side-by-side:
  LEFT:  "My Auctions" TableView showing the seller's own auctions.
  RIGHT: Two form cards — "New Item" and "New Auction".

Implements Refreshable: refresh() reloads both the auction list and the item
ComboBox (via loadMyAuctions() and loadMyItems()) each time the screen is visited.

IMPORT NOTES:
  - ItemDTO: represents an item in the ComboBox for auction creation.
  - StringConverter: converts ItemDTO to a display string for the ComboBox.
  - CreateItemRequest / CreateAuctionRequest: form data sent to the server.
  - ItemsResponse: the server's response containing the seller's items list.
  - DateTimeFormatter / LocalDateTime: used to format the current time as default
    start time when the user leaves the start time field blank.
 */

import com.auction.client.network.ServerConnection;
import com.auction.client.session.ClientSession;
import com.auction.client.util.AlertUtil;
import com.auction.client.util.SceneManager;
import com.auction.common.dto.AuctionDTO;
import com.auction.common.dto.ItemDTO;
import com.auction.common.protocol.Message;
import com.auction.common.protocol.MessageType;
import com.auction.common.request.Requests.*;
import com.auction.common.request.Responses.*;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class SellerDashboardController implements SceneManager.Refreshable {

    // ── My auctions table ─────────────────────────────────────────────────────
    @FXML private TableView<AuctionDTO>          auctionTable;
    @FXML private TableColumn<AuctionDTO, String> colItem;
    @FXML private TableColumn<AuctionDTO, String> colPrice;
    @FXML private TableColumn<AuctionDTO, String> colStatus;
    @FXML private TableColumn<AuctionDTO, String> colEnds;
    @FXML private Label                          userLabel;

    // ── Create item form ──────────────────────────────────────────────────────
    @FXML private TextField     itemNameField;
    @FXML private TextArea      itemDescField;
    @FXML private ComboBox<String> itemCategoryCombo;
    @FXML private TextField     itemExtraField;

    // ── Create auction form ───────────────────────────────────────────────────
    @FXML private ComboBox<ItemDTO> itemCombo;
    @FXML private TextField         startPriceField;
    @FXML private TextField         startTimeField;
    @FXML private TextField         endTimeField;

    @FXML private Label statusLabel;

    private final ObservableList<AuctionDTO> myAuctions = FXCollections.observableArrayList();
    private final ObservableList<ItemDTO>    myItems    = FXCollections.observableArrayList();

    @FXML
    private void initialize() {
        colItem.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getItem() != null
                        ? c.getValue().getItem().getName() : ""));
        colPrice.setCellValueFactory(c ->
                new SimpleStringProperty(String.format("$%.2f", c.getValue().getCurrentPrice())));
        colStatus.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getStatus()));
        colEnds.setCellValueFactory(c ->
                new SimpleStringProperty(fmt(c.getValue().getEndTime())));
        auctionTable.setItems(myAuctions);

        itemCategoryCombo.getItems().addAll("ELECTRONICS", "ART", "VEHICLE");
        itemCategoryCombo.setValue("ELECTRONICS");

        itemCombo.setItems(myItems);
        itemCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(ItemDTO i) { return i == null ? "" : i.getName(); }
            @Override public ItemDTO fromString(String s) { return null; }
        });
    }

    @Override
    public void refresh() {
        userLabel.setText("Seller: " + ClientSession.getInstance().getCurrentUser().getUsername());
        loadMyAuctions();
        loadMyItems();
    }

    // ── Create item ───────────────────────────────────────────────────────────

    @FXML
    private void onCreateItem() {
        String name     = itemNameField.getText().trim();
        String desc     = itemDescField.getText().trim();
        String category = itemCategoryCombo.getValue();
        String extra    = itemExtraField.getText().trim();

        if (name.isEmpty()) { statusLabel.setText("Item name is required."); return; }

        ServerConnection conn = ClientSession.getInstance().getConnection();
        CreateItemRequest req = new CreateItemRequest();
        req.name = name; req.description = desc;
        req.category = category; req.extraData = extra;

        Message msg = Message.of(MessageType.CREATE_ITEM, req, conn.getGson());
        conn.send(msg).whenCompleteAsync((resp, ex) -> Platform.runLater(() -> {
            if (ex != null) { statusLabel.setText("Error: " + ex.getMessage()); return; }
            if (resp.getType() == MessageType.ERROR) {
                statusLabel.setText(resp.parsePayload(conn.getGson(), ErrorResponse.class).message);
                return;
            }
            ItemDTO created = resp.parsePayload(conn.getGson(), ItemDTO.class);
            myItems.add(created);
            statusLabel.setText("Item '" + created.getName() + "' created.");
            itemNameField.clear(); itemDescField.clear(); itemExtraField.clear();
        }));
    }

    // ── Create auction ────────────────────────────────────────────────────────

    @FXML
    private void onCreateAuction() {
        ItemDTO selectedItem = itemCombo.getValue();
        String  priceText   = startPriceField.getText().trim();
        String  startText   = startTimeField.getText().trim();
        String  endText     = endTimeField.getText().trim();

        if (selectedItem == null) { statusLabel.setText("Select an item."); return; }
        double price;
        try { price = Double.parseDouble(priceText); }
        catch (NumberFormatException e) { statusLabel.setText("Invalid starting price."); return; }

        ServerConnection conn = ClientSession.getInstance().getConnection();
        CreateAuctionRequest req = new CreateAuctionRequest();
        req.itemId       = selectedItem.getId();
        req.startingPrice = price;
        req.startTime    = startText.isEmpty()
                ? LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                : startText;
        req.endTime      = endText;

        Message msg = Message.of(MessageType.CREATE_AUCTION, req, conn.getGson());
        conn.send(msg).whenCompleteAsync((resp, ex) -> Platform.runLater(() -> {
            if (ex != null) { statusLabel.setText("Error: " + ex.getMessage()); return; }
            if (resp.getType() == MessageType.ERROR) {
                statusLabel.setText(resp.parsePayload(conn.getGson(), ErrorResponse.class).message);
                return;
            }
            AuctionDTO created = resp.parsePayload(conn.getGson(), AuctionDTO.class);
            myAuctions.add(0, created);
            statusLabel.setText("Auction #" + created.getId() + " created.");
            startPriceField.clear(); startTimeField.clear(); endTimeField.clear();
        }));
    }

    // ── Cancel auction ────────────────────────────────────────────────────────

    @FXML
    private void onCancelAuction() {
        AuctionDTO selected = auctionTable.getSelectionModel().getSelectedItem();
        if (selected == null) { statusLabel.setText("Select an auction to cancel."); return; }
        if (!AlertUtil.confirm("Cancel Auction", "Cancel auction #" + selected.getId() + "?")) return;

        ServerConnection conn = ClientSession.getInstance().getConnection();
        Message msg = Message.of(MessageType.CANCEL_AUCTION,
                new CancelAuctionRequest(selected.getId()), conn.getGson());

        conn.send(msg).whenCompleteAsync((resp, ex) -> Platform.runLater(() -> {
            if (ex != null) { statusLabel.setText("Error: " + ex.getMessage()); return; }
            if (resp.getType() == MessageType.ERROR) {
                statusLabel.setText(resp.parsePayload(conn.getGson(), ErrorResponse.class).message);
                return;
            }
            statusLabel.setText("Auction cancelled.");
            loadMyAuctions();
        }));
    }

    @FXML
    private void onViewAuctions() {
        SceneManager.switchTo(SceneManager.View.AUCTION_LIST);
    }

    @FXML
    private void onLogout() {
        ClientSession session = ClientSession.getInstance();
        session.getConnection().send(
                Message.of(MessageType.LOGOUT, com.auction.common.request.EmptyPayload.INSTANCE, session.getConnection().getGson()));
        session.logout();
        SceneManager.evictAll();
        SceneManager.switchTo(SceneManager.View.LOGIN);
    }

    private void loadMyItems() {
        ServerConnection conn = ClientSession.getInstance().getConnection();
        Message msg = Message.of(MessageType.GET_SELLER_ITEMS,
                com.auction.common.request.EmptyPayload.INSTANCE, conn.getGson());
        conn.send(msg).whenCompleteAsync((resp, ex) -> Platform.runLater(() -> {
            if (ex != null || resp.getType() == MessageType.ERROR) return;
            com.auction.common.request.Responses.ItemsResponse r =
                    resp.parsePayload(conn.getGson(),
                            com.auction.common.request.Responses.ItemsResponse.class);
            if (r.items != null) {
                myItems.setAll(r.items);
            }
        }));
    }

    private void loadMyAuctions() {
        ServerConnection conn = ClientSession.getInstance().getConnection();
        Message msg = Message.of(MessageType.GET_SELLER_AUCTIONS, com.auction.common.request.EmptyPayload.INSTANCE, conn.getGson());
        conn.send(msg).whenCompleteAsync((resp, ex) -> Platform.runLater(() -> {
            if (ex != null || resp.getType() == MessageType.ERROR) return;
            AuctionsResponse r = resp.parsePayload(conn.getGson(), AuctionsResponse.class);
            myAuctions.setAll(r.auctions != null ? r.auctions : List.of());
        }));
    }

    private String fmt(String iso) {
        if (iso == null) return "";
        return iso.replace("T", " ").substring(0, Math.min(16, iso.length()));
    }
}
