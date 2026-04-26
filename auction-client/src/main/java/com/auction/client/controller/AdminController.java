package com.auction.client.controller;

/**
 * FILE ROLE:
FILE ROLE: Controller for the admin panel screen (admin_panel.fxml).

Displays a TableView of all registered users with their role and active status.
Admin can select a user and click "Ban Selected" to deactivate their account.

Implements Refreshable: calls loadUsers() on every visit to show the latest data.

IMPORT NOTES:
  - UsersResponse: the server's response payload for GET_USERS.
  - BanUserRequest: carries the target user's id for the BAN_USER message.
  - SimpleStringProperty: wires UserDTO fields to TableColumn cell factories.
  - AlertUtil.confirm: shows "Are you sure?" before banning.
 */

import com.auction.client.network.ServerConnection;
import com.auction.client.session.ClientSession;
import com.auction.client.util.AlertUtil;
import com.auction.client.util.SceneManager;
import com.auction.common.dto.UserDTO;
import com.auction.common.protocol.Message;
import com.auction.common.protocol.MessageType;
import com.auction.common.request.Requests.BanUserRequest;
import com.auction.common.request.Responses.*;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.util.List;

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

        conn.send(msg).whenCompleteAsync((resp, ex) -> Platform.runLater(() -> {
            if (ex != null) { statusLabel.setText("Error: " + ex.getMessage()); return; }
            if (resp.getType() == MessageType.ERROR) {
                statusLabel.setText(resp.parsePayload(conn.getGson(), ErrorResponse.class).message);
                return;
            }
            statusLabel.setText("User '" + selected.getUsername() + "' banned.");
            loadUsers();
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

    private void loadUsers() {
        ServerConnection conn = ClientSession.getInstance().getConnection();
        Message msg = Message.of(MessageType.GET_USERS, com.auction.common.request.EmptyPayload.INSTANCE, conn.getGson());
        conn.send(msg).whenCompleteAsync((resp, ex) -> Platform.runLater(() -> {
            if (ex != null || resp.getType() == MessageType.ERROR) return;
            UsersResponse r = resp.parsePayload(conn.getGson(), UsersResponse.class);
            users.setAll(r.users != null ? r.users : List.of());
        }));
    }
}
