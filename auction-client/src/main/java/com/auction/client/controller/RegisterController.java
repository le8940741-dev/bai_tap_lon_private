package com.auction.client.controller;

/**
 * FXML controller for {@code register.fxml}.
 *
 * <p>Gathers form fields into {@link com.auction.common.request.Requests.RegisterRequest}, posts
 * {@link com.auction.common.protocol.MessageType#REGISTER}, and surfaces shared server error text
 * if the server rejects input (duplicate username, weak password, etc.).</p>
 */
import com.auction.client.network.ServerConnection;
import com.auction.client.session.ClientSession;
import com.auction.client.util.AlertUtil;
import com.auction.client.util.SceneManager;
import com.auction.common.protocol.Message;
import com.auction.common.protocol.MessageType;
import com.auction.common.request.Requests.RegisterRequest;
import javafx.fxml.FXML;
import javafx.scene.control.*;

/**
 * Collects registration form data, validates simple client-side fields, and posts REGISTER to the server.
 *
 * <p>SceneManager creates this controller for register.fxml from the login screen; JavaFX calls
 * initialize() and the button handlers declared in FXML.</p>
 */
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

        // JavaFX controls must be updated on the FX thread; the connection helper does that hop.
        conn.sendOnFxThread(msg, (response, ex) -> {
            registerButton.setDisable(false);
            if (ex != null) { statusLabel.setText("Error: " + ex.getMessage()); return; }
            if (response.getType() == MessageType.ERROR) {
                statusLabel.setText(conn.errorMessage(response));
                return;
            }
            AlertUtil.info("Registered", "Account created! Please log in.");
            SceneManager.switchTo(SceneManager.View.LOGIN);
        });
    }

    @FXML
    private void onBackToLogin() {
        SceneManager.switchTo(SceneManager.View.LOGIN);
    }
}
