package com.auction.client;

import com.auction.client.network.ServerConnection;
import com.auction.client.session.ClientSession;
import com.auction.client.util.AlertUtil;
import com.auction.client.util.SceneManager;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

/**
 * JavaFX {@link Application} subclass — this is the first class the client JVM runs
 * ({@link #main(String[])} calls {@link Application#launch} which reflects and instantiates this type).
 *
 * <p><b>Startup story:</b> {@link #start(Stage)} wires {@link com.auction.client.util.SceneManager},
 * opens {@link com.auction.client.network.ServerConnection}, stores it in {@link com.auction.client.session.ClientSession},
 * then loads the login FXML. If the TCP handshake fails, the user sees an alert instead of a broken UI.</p>
 *
 * <p><b>System properties:</b> {@code -Dserver.host} and {@code -Dserver.port} override the default
 * {@code localhost:9090} so you can point the desktop client at a teammate’s machine.</p>
 */
public final class ClientMain extends Application {

    // Read server coordinates from system properties; fall back to localhost:9090.
    private static final String SERVER_HOST =
            System.getProperty("server.host", "localhost");
    private static final int SERVER_PORT =
            Integer.parseInt(System.getProperty("server.port", "9090"));

    /** Called by JavaFX after launch() creates the main window. */
    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Auction System");
        primaryStage.setMinWidth(900);   // keep the UI usable
        primaryStage.setMinHeight(620);

        // Give SceneManager a reference to the window so it can swap scenes.
        SceneManager.init(primaryStage);

        // Try to connect to the server before showing any UI.
        try {
            ServerConnection conn = new ServerConnection();
            conn.connect(SERVER_HOST, SERVER_PORT);
            ClientSession.getInstance().setConnection(conn);
        } catch (Exception e) {
            // If the server is unavailable, show the error and stop startup.
            AlertUtil.error("Connection Failed",
                    "Cannot connect to server at " + SERVER_HOST + ":" + SERVER_PORT
                    + "\n\n" + e.getMessage());
            Platform.exit(); // clean JavaFX shutdown
            return;
        }

        // Server connected, so the user can log in.
        SceneManager.switchTo(SceneManager.View.LOGIN);

        // When the user closes the window, disconnect the socket before exiting.
        primaryStage.setOnCloseRequest(e -> {
            ClientSession.getInstance().getConnection().disconnect();
            Platform.exit();
        });
    }

    /** Standard Java entry point. JavaFX calls start() after launch(). */
    public static void main(String[] args) {
        launch(args); // hands control to the JavaFX runtime
    }
}
