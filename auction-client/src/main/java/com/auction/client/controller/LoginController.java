package com.auction.client.controller;

import com.auction.client.network.ServerConnection;
import com.auction.client.session.ClientSession;
import com.auction.client.util.AlertUtil;
import com.auction.client.util.SceneManager;

import com.auction.common.dto.UserDTO;
import com.auction.common.protocol.Message;
import com.auction.common.protocol.MessageType;
import com.auction.common.request.Requests.LoginRequest;

import javafx.fxml.FXML;
import javafx.scene.control.*;

/**
 * FXML controller for {@code login.fxml} (the “C” in MVC for this screen).
 *
 * <p>Builds a {@link com.auction.common.protocol.Message} with {@link MessageType#LOGIN}, sends it
 * through {@link com.auction.client.session.ClientSession#getConnection()}, then uses
 * {@code sendOnFxThread} so network work never blocks the JavaFX thread. On success it stores
 * {@link com.auction.common.dto.UserDTO} and calls {@link com.auction.client.util.SceneManager#switchTo}
 * with the view that matches {@code user.getRole()}.</p>
 */
public final class LoginController {

    @FXML private TextField     usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Button        loginButton;
    @FXML private Label         statusLabel;

    /** Called when the user clicks LOG IN (declared as onAction="#onLogin" in FXML). */
    @FXML
    private void onLogin() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();

        // Avoid a server call when the form is incomplete.
        if (username.isEmpty() || password.isEmpty()) {
            statusLabel.setText("Please enter username and password.");
            return;
        }

        loginButton.setDisable(true);  // prevent double-click spam
        statusLabel.setText("Connecting…");

        ServerConnection conn = ClientSession.getInstance().getConnection();
        // Wrap the login data in a protocol message.
        Message msg = Message.of(MessageType.LOGIN,
                new LoginRequest(username, password), conn.getGson());

        // sendOnFxThread: keeps socket work off the JavaFX thread, then safely updates controls here.
        conn.sendOnFxThread(msg, (response, ex) -> {
            loginButton.setDisable(false); // re-enable regardless of outcome

            if (ex != null) { // network error (server unreachable, connection dropped)
                statusLabel.setText("Connection error: " + ex.getMessage());
                return;
            }
            if (response.getType() == MessageType.ERROR) {
                // Show the server's login error.
                statusLabel.setText(conn.errorMessage(response));
                return;
            }
            // Login succeeded. Save the user and open the right screen.
            UserDTO user = response.parsePayload(conn.getGson(), UserDTO.class);
            ClientSession.getInstance().setCurrentUser(user);
            SceneManager.evictAll(); // clear any cached screens from a previous session
            navigateByRole(user.getRole());
        });
    }

    /** Called when the user clicks "Create an account" (onAction="#onGoRegister" in FXML). */
    @FXML
    private void onGoRegister() {
        SceneManager.switchTo(SceneManager.View.REGISTER);
    }

    /** Navigate to the appropriate screen based on the user's role. */
    private void navigateByRole(String role) {
        switch (role) {
            case "SELLER" -> SceneManager.switchTo(SceneManager.View.SELLER_DASHBOARD);
            case "ADMIN"  -> SceneManager.switchTo(SceneManager.View.ADMIN_PANEL);
            default       -> SceneManager.switchTo(SceneManager.View.AUCTION_LIST);
        }
    }
}
