package com.auction.client.controller;

/**
 * FXML controller for {@code seller_dashboard.fxml}.
 *
 * <p>Demonstrates several request types in one screen: {@code CREATE_ITEM}, {@code CREATE_AUCTION},
 * {@code CANCEL_AUCTION}, plus seller-scoped queries. Each button handler follows the same recipe:
 * build {@link com.auction.common.protocol.Message}, call ServerConnection.sendOnFxThread(),
 * parse success payloads into DTOs, or show the shared server error payload.</p>
 */
import com.auction.client.network.ServerConnection;
import com.auction.client.session.ClientSession;
import com.auction.client.util.AlertUtil;
import com.auction.client.util.DisplayFormat;
import com.auction.client.util.SceneManager;
import com.auction.client.util.SessionActions;
import com.auction.common.dto.AuctionDTO;
import com.auction.common.dto.ItemDTO;
import com.auction.common.protocol.Message;
import com.auction.common.protocol.MessageType;
import com.auction.common.request.Requests.*;
import com.auction.common.request.Responses.*;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Coordinates the seller dashboard: item creation, auction creation, cancellation, and seller-only lists.
 *
 * <p>SceneManager creates this controller for seller_dashboard.fxml after seller login; JavaFX calls
 * initialize(), and refresh() reloads the seller's auctions and inventory whenever the view appears.</p>
 */
public final class SellerDashboardController implements SceneManager.Refreshable {

    // My auctions table
    @FXML private TableView<AuctionDTO>          auctionTable;
    @FXML private TableColumn<AuctionDTO, String> colItem;
    @FXML private TableColumn<AuctionDTO, String> colPrice;
    @FXML private TableColumn<AuctionDTO, String> colStatus;
    @FXML private TableColumn<AuctionDTO, String> colEnds;
    @FXML private Label                          userLabel;

    // Create item form
    @FXML private TextField     itemNameField;
    @FXML private TextArea      itemDescField;
    @FXML private ComboBox<String> itemCategoryCombo;
    @FXML private TextField     itemImageField;
    @FXML private TextField     itemExtraField;

    // Create auction form
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
                new SimpleStringProperty(DisplayFormat.isoToMinuteLabel(c.getValue().getEndTime())));
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

    // Create item

    @FXML
    private void onCreateItem() {
        String name     = itemNameField.getText().trim();
        String desc     = itemDescField.getText().trim();
        String category = itemCategoryCombo.getValue();
        String imageUrl = itemImageField.getText().trim();
        String extra    = itemExtraField.getText().trim();

        if (name.isEmpty()) { statusLabel.setText("Item name is required."); return; }

        ServerConnection conn = ClientSession.getInstance().getConnection();
        CreateItemRequest req = new CreateItemRequest();
        req.name = name; req.description = desc;
        req.category = category;
        req.imageUrl = imageUrl.isEmpty() ? null : imageUrl;
        req.extraData = extra;

        Message msg = Message.of(MessageType.CREATE_ITEM, req, conn.getGson());
        conn.sendOnFxThread(msg, (resp, ex) -> {
            if (ex != null) { statusLabel.setText("Error: " + ex.getMessage()); return; }
            if (resp.getType() == MessageType.ERROR) {
                statusLabel.setText(conn.errorMessage(resp));
                return;
            }
            ItemDTO created = resp.parsePayload(conn.getGson(), ItemDTO.class);
            myItems.add(created);
            statusLabel.setText("Item '" + created.getName() + "' created.");
            itemNameField.clear();
            itemDescField.clear();
            itemImageField.clear();
            itemExtraField.clear();
        });
    }

    // Create auction

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
        conn.sendOnFxThread(msg, (resp, ex) -> {
            if (ex != null) { statusLabel.setText("Error: " + ex.getMessage()); return; }
            if (resp.getType() == MessageType.ERROR) {
                statusLabel.setText(conn.errorMessage(resp));
                return;
            }
            AuctionDTO created = resp.parsePayload(conn.getGson(), AuctionDTO.class);
            myAuctions.add(0, created);
            statusLabel.setText("Auction #" + created.getId() + " created.");
            startPriceField.clear(); startTimeField.clear(); endTimeField.clear();
        });
    }

    // Cancel auction

    @FXML
    private void onCancelAuction() {
        AuctionDTO selected = auctionTable.getSelectionModel().getSelectedItem();
        if (selected == null) { statusLabel.setText("Select an auction to cancel."); return; }
        if (!AlertUtil.confirm("Cancel Auction", "Cancel auction #" + selected.getId() + "?")) return;

        ServerConnection conn = ClientSession.getInstance().getConnection();
        Message msg = Message.of(MessageType.CANCEL_AUCTION,
                new CancelAuctionRequest(selected.getId()), conn.getGson());

        conn.sendOnFxThread(msg, (resp, ex) -> {
            if (ex != null) { statusLabel.setText("Error: " + ex.getMessage()); return; }
            if (resp.getType() == MessageType.ERROR) {
                statusLabel.setText(conn.errorMessage(resp));
                return;
            }
            statusLabel.setText("Auction cancelled.");
            loadMyAuctions();
        });
    }

    @FXML
    private void onViewAuctions() {
        SceneManager.switchTo(SceneManager.View.AUCTION_LIST);
    }

    @FXML
    private void onLogout() {
        SessionActions.logoutToLogin();
    }

    private void loadMyItems() {
        ServerConnection conn = ClientSession.getInstance().getConnection();
        Message msg = Message.of(MessageType.GET_SELLER_ITEMS,
                com.auction.common.request.EmptyPayload.INSTANCE, conn.getGson());
        conn.sendOnFxThread(msg, (resp, ex) -> {
            if (ex != null || resp.getType() == MessageType.ERROR) return;
            com.auction.common.request.Responses.ItemsResponse r =
                    resp.parsePayload(conn.getGson(),
                            com.auction.common.request.Responses.ItemsResponse.class);
            if (r.items != null) {
                myItems.setAll(r.items);
            }
        });
    }

    private void loadMyAuctions() {
        ServerConnection conn = ClientSession.getInstance().getConnection();
        Message msg = Message.of(MessageType.GET_SELLER_AUCTIONS, com.auction.common.request.EmptyPayload.INSTANCE, conn.getGson());
        conn.sendOnFxThread(msg, (resp, ex) -> {
            if (ex != null || resp.getType() == MessageType.ERROR) return;
            AuctionsResponse r = resp.parsePayload(conn.getGson(), AuctionsResponse.class);
            myAuctions.setAll(r.auctions != null ? r.auctions : List.of());
        });
    }
}
