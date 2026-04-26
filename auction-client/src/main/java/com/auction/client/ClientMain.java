package com.auction.client;

import com.auction.client.network.ServerConnection; // TCP connection to the server
import com.auction.client.session.ClientSession;    // singleton holding connection + user
import com.auction.client.util.AlertUtil;           // modal error dialogs
import com.auction.client.util.SceneManager;        // screen switcher

// JavaFX application lifecycle classes:
import javafx.application.Application; // base class for JavaFX apps; manages FX toolkit
import javafx.application.Platform;   // Platform.exit() = clean JavaFX shutdown
import javafx.stage.Stage;            // the OS window

/**
 * FILE ROLE: JavaFX application entry point — the first class the JVM calls.
 *
 * HOW JAVAFX WORKS:
 *   The JVM calls main() → launch(args) → JavaFX toolkit starts →
 *   start(Stage) is called on the FX Application Thread.
 *   From that point, all UI code must run on the FX thread.
 *
 * STARTUP SEQUENCE:
 *   1. Read server host/port from system properties (overridable on command line).
 *   2. Connect to the server via ServerConnection.connect().
 *      If this fails, show an error dialog and exit — there's no point starting
 *      the UI if we can't talk to the server.
 *   3. Store the connection in ClientSession so all controllers can reach it.
 *   4. Configure the Stage (title, minimum size).
 *   5. Show the LOGIN screen.
 *   6. Register a close handler to disconnect cleanly when the window is closed.
 *
 * COMMAND-LINE CUSTOMISATION:
 *   java -jar ... -Dserver.host=192.168.1.10 -Dserver.port=8080
 *
 * USED BY: JVM (as the main class declared in the fat-jar manifest).
 */
public final class ClientMain extends Application {

    // Read server coordinates from system properties; fall back to localhost:9090.
    private static final String SERVER_HOST =
            System.getProperty("server.host", "localhost");
    private static final int SERVER_PORT =
            Integer.parseInt(System.getProperty("server.port", "9090"));

    /**
     * Called by the JavaFX toolkit on the FX Application Thread after launch().
     * 'primaryStage' is the OS window provided by the FX runtime.
     */
    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Auction System");
        primaryStage.setMinWidth(900);   // prevent the window from being resized too small
        primaryStage.setMinHeight(620);

        // Give SceneManager a reference to the window so it can swap scenes.
        SceneManager.init(primaryStage);

        // Try to connect to the server before showing any UI.
        try {
            ServerConnection conn = new ServerConnection();
            conn.connect(SERVER_HOST, SERVER_PORT);
            ClientSession.getInstance().setConnection(conn);
        } catch (Exception e) {
            // Can't reach the server — show an error and exit gracefully.
            AlertUtil.error("Connection Failed",
                    "Cannot connect to server at " + SERVER_HOST + ":" + SERVER_PORT
                    + "\n\n" + e.getMessage());
            Platform.exit(); // clean JavaFX shutdown
            return;
        }

        // Server connected — show the login screen.
        SceneManager.switchTo(SceneManager.View.LOGIN);

        // When the user closes the window, disconnect the socket before exiting.
        primaryStage.setOnCloseRequest(e -> {
            ClientSession.getInstance().getConnection().disconnect();
            Platform.exit();
        });
    }

    /**
     * Standard Java main — delegates to JavaFX's launch() which starts the FX toolkit,
     * then calls start() on the FX Application Thread.
     */
    public static void main(String[] args) {
        launch(args); // hands control to the JavaFX runtime
    }
}
