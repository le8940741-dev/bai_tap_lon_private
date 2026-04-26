package com.auction.client.controller;

import com.auction.client.network.ServerConnection; // to send the LOGIN message
import com.auction.client.session.ClientSession;    // stores the returned UserDTO
import com.auction.client.util.AlertUtil;           // error dialogs (unused here but available)
import com.auction.client.util.SceneManager;        // navigates to the next screen after login

import com.auction.common.dto.UserDTO;              // the user data returned by the server
import com.auction.common.protocol.Message;         // TCP message envelope
import com.auction.common.protocol.MessageType;     // LOGIN / LOGIN_RESPONSE / ERROR
import com.auction.common.request.Requests.LoginRequest;   // payload sent to server
import com.auction.common.request.Responses.ErrorResponse; // payload received on failure

// JavaFX application thread scheduling — callbacks from CompletableFuture run on a
// pool thread; Platform.runLater() schedules UI updates on the FX thread.
import javafx.application.Platform;

// FXML-injected UI controls — @FXML links Java field to the XML element with the same fx:id.
import javafx.fxml.FXML;
import javafx.scene.control.*;

/**
 * FILE ROLE: Controller for the login screen (login.fxml).
 *
 * MVC ROLE: This is the Controller in Model-View-Controller.
 *   - Model:      the server (UserService.login) verifies credentials.
 *   - View:       login.fxml declares the UI layout.
 *   - Controller: this class handles user actions and updates the view.
 *
 * HOW FXML WIRING WORKS:
 *   FXMLLoader reads login.fxml, instantiates this class, and injects the UI
 *   controls annotated with @FXML.  Methods annotated with @FXML are called
 *   when the corresponding onAction event fires in the FXML (e.g. button click).
 *
 * ASYNC PATTERN:
 *   Clicking "LOG IN" disables the button, sends the request, and returns immediately.
 *   The CompletableFuture's whenCompleteAsync callback runs when the server responds.
 *   Platform.runLater() is required inside the callback because the callback runs on
 *   a pool thread, not the FX thread — UI updates must be on the FX thread.
 *
 * NAVIGATION AFTER LOGIN:
 *   Role determines which screen is shown:
 *     BIDDER → AUCTION_LIST
 *     SELLER → SELLER_DASHBOARD
 *     ADMIN  → ADMIN_PANEL
 */
public final class LoginController {

    @FXML private TextField     usernameField; // bound to <TextField fx:id="usernameField"> in FXML
    @FXML private PasswordField passwordField; // masks the typed password
    @FXML private Button        loginButton;   // triggers onLogin()
    @FXML private Label         statusLabel;   // displays errors below the button

    /** Called when the user clicks LOG IN (declared as onAction="#onLogin" in FXML). */
    @FXML
    private void onLogin() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();

        // Client-side pre-validation — avoids an unnecessary network round-trip.
        if (username.isEmpty() || password.isEmpty()) {
            statusLabel.setText("Please enter username and password.");
            return;
        }

        loginButton.setDisable(true);  // prevent double-click spam
        statusLabel.setText("Connecting…");

        ServerConnection conn = ClientSession.getInstance().getConnection();
        // Wrap the LoginRequest in a Message envelope with a fresh UUID requestId.
        Message msg = Message.of(MessageType.LOGIN,
                new LoginRequest(username, password), conn.getGson());

        // Send and attach a callback — non-blocking on the FX thread.
        conn.send(msg).whenCompleteAsync((response, ex) -> Platform.runLater(() -> {
            loginButton.setDisable(false); // re-enable regardless of outcome

            if (ex != null) { // network error (server unreachable, connection dropped)
                statusLabel.setText("Connection error: " + ex.getMessage());
                return;
            }
            if (response.getType() == MessageType.ERROR) {
                // Server rejected the login — display the reason from ErrorResponse.message.
                String err = response.parsePayload(conn.getGson(), ErrorResponse.class).message;
                statusLabel.setText(err);
                return;
            }
            // Success — parse the UserDTO, store in session, navigate.
            UserDTO user = response.parsePayload(conn.getGson(), UserDTO.class);
            ClientSession.getInstance().setCurrentUser(user);
            SceneManager.evictAll(); // clear any cached screens from a previous session
            navigateByRole(user.getRole());
        }));
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
