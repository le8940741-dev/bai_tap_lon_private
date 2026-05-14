package com.auction.client.controller;

/**
 * FXML controller for {@code admin_panel.fxml} — user moderation table.
 *
 * <p>Only reachable for {@code ADMIN} roles. Issues {@link com.auction.common.protocol.MessageType#GET_USERS}
 * on refresh and {@link com.auction.common.protocol.MessageType#BAN_USER} when a row is banned.
 * Like other controllers it marshals async responses back onto the FX thread through ServerConnection.sendOnFxThread().</p>
 */
import com.auction.client.network.ServerConnection;
import com.auction.client.session.ClientSession;
import com.auction.client.util.AlertUtil;
import com.auction.client.util.SceneManager;
import com.auction.client.util.SessionActions;
import com.auction.common.dto.UserDTO;
import com.auction.common.protocol.Message;
import com.auction.common.protocol.MessageType;
import com.auction.common.request.Requests.BanUserRequest;
import com.auction.common.request.Responses.*;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.util.List;

/**
 * Displays the admin moderation table and sends ban requests for selected non-admin users.
 *
 * <p>SceneManager creates this controller for admin_panel.fxml after login; JavaFX calls
 * initialize(), and refresh() reloads users whenever the admin panel is shown.</p>
 */
public final class AdminController implements SceneManager.Refreshable {

    @FXML private TableView<UserDTO>           userTable;
    @FXML private TableColumn<UserDTO, String> colId;
    @FXML private TableColumn<UserDTO, String> colUsername;
    @FXML private TableColumn<UserDTO, String> colEmail;
    @FXML private TableColumn<UserDTO, String> colRole;
    @FXML private TableColumn<UserDTO, String> colActive;
    @FXML private Label                        statusLabel;
    @FXML private Label                        userLabel;

    private final ObservableList<UserDTO> users = FXCollections.observableArrayList();

    @FXML
    private void initialize() {
        colId.setCellValueFactory(c ->
                new SimpleStringProperty(String.valueOf(c.getValue().getId())));
        colUsername.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getUsername()));
        colEmail.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getEmail()));
        colRole.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().getRole()));
        colActive.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().isActive() ? "Active" : "Banned"));
        userTable.setItems(users);
    }

    @Override
    public void refresh() {
        userLabel.setText("Admin: " + ClientSession.getInstance().getCurrentUser().getUsername());
        loadUsers();
    }

    @FXML
    private void onRefresh() { loadUsers(); }

    @FXML
    private void onBanUser() {
        UserDTO selected = userTable.getSelectionModel().getSelectedItem();
        if (selected == null) { statusLabel.setText("Select a user first."); return; }
        if ("ADMIN".equals(selected.getRole())) {
            statusLabel.setText("Cannot ban another admin."); return;
        }
        if (!AlertUtil.confirm("Ban User", "Ban user '" + selected.getUsername() + "'?")) return;

        ServerConnection conn = ClientSession.getInstance().getConnection();
        Message msg = Message.of(MessageType.BAN_USER,
                new BanUserRequest(selected.getId()), conn.getGson());

        // The helper runs this lambda on the JavaFX thread, which is required before changing labels.
        conn.sendOnFxThread(msg, (resp, ex) -> {
            if (ex != null) { statusLabel.setText("Error: " + ex.getMessage()); return; }
            if (resp.getType() == MessageType.ERROR) {
                statusLabel.setText(conn.errorMessage(resp));
                return;
            }
            statusLabel.setText("User '" + selected.getUsername() + "' banned.");
            loadUsers();
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

    private void loadUsers() {
        ServerConnection conn = ClientSession.getInstance().getConnection();
        Message msg = Message.of(MessageType.GET_USERS, com.auction.common.request.EmptyPayload.INSTANCE, conn.getGson());
        conn.sendOnFxThread(msg, (resp, ex) -> {
            if (ex != null || resp.getType() == MessageType.ERROR) return;
            UsersResponse r = resp.parsePayload(conn.getGson(), UsersResponse.class);
            users.setAll(r.users != null ? r.users : List.of());
        });
    }
}
