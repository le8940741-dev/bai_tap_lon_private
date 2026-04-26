package com.auction.client.controller;

/**
 * FILE ROLE:
FILE ROLE: Controller for the registration screen (register.fxml).

Collects username, email, password, password-confirm, and role (BIDDER or SELLER).
Validates that passwords match client-side, then sends REGISTER to the server.
On success, shows an info dialog and navigates back to the login screen.

IMPORT NOTES:
  - ServerConnection: sends the REGISTER message.
  - SceneManager: navigates back to LOGIN after successful registration.
  - RegisterRequest: the payload carrying all form fields to the server.
  - ErrorResponse: the server's error payload if registration fails.
  - Platform.runLater: required because the callback runs off the FX thread.
  - AlertUtil: shows the "Account created!" info dialog.
 */

import com.auction.client.network.ServerConnection;
import com.auction.client.session.ClientSession;
import com.auction.client.util.AlertUtil;
import com.auction.client.util.SceneManager;
import com.auction.common.protocol.Message;
import com.auction.common.protocol.MessageType;
import com.auction.common.request.Requests.RegisterRequest;
import com.auction.common.request.Responses.ErrorResponse;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;

public final class RegisterController {

    @FXML private TextField     usernameField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmField;
    @FXML private TextField     emailField;
    @FXML private ComboBox<String> roleCombo;
    @FXML private Button        registerButton;
    @FXML private Label         statusLabel;

    @FXML
    private void initialize() {
        roleCombo.getItems().addAll("BIDDER", "SELLER");
        roleCombo.setValue("BIDDER");
    }

    @FXML
    private void onRegister() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();
        String confirm  = confirmField.getText();
        String email    = emailField.getText().trim();
        String role     = roleCombo.getValue();

        if (username.isEmpty() || password.isEmpty() || email.isEmpty()) {
            statusLabel.setText("All fields are required.");
            return;
        }
        if (!password.equals(confirm)) {
            statusLabel.setText("Passwords do not match.");
            return;
        }

        registerButton.setDisable(true);
        statusLabel.setText("Registering…");

        ServerConnection conn = ClientSession.getInstance().getConnection();
        Message msg = Message.of(MessageType.REGISTER,
                new RegisterRequest(username, password, email, role), conn.getGson());

        conn.send(msg).whenCompleteAsync((response, ex) -> Platform.runLater(() -> {
            registerButton.setDisable(false);
            if (ex != null) { statusLabel.setText("Error: " + ex.getMessage()); return; }
            if (response.getType() == MessageType.ERROR) {
                statusLabel.setText(response.parsePayload(conn.getGson(), ErrorResponse.class).message);
                return;
            }
            AlertUtil.info("Registered", "Account created! Please log in.");
            SceneManager.switchTo(SceneManager.View.LOGIN);
        }));
    }

    @FXML
    private void onBackToLogin() {
        SceneManager.switchTo(SceneManager.View.LOGIN);
    }
}
