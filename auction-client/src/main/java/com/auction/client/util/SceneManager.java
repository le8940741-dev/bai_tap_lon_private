package com.auction.client.util;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * MVC navigation helper: loads FXML documents, caches roots, swaps {@link Scene} on the primary {@link Stage}.
 *
 * <p><b>Why a manager class?</b> Controllers should not each own {@code new Stage()} — this keeps
 * window sizing, min bounds, and back-stack behaviour consistent. {@link View} is an {@code enum}
 * listing every screen path so typos fail at compile time.</p>
 *
 * <p><b>Interaction:</b> {@link com.auction.client.ClientMain} calls {@link #init(Stage)} once.
 * After login, {@link com.auction.client.controller.LoginController} calls {@link #switchTo(View)}
 * to show the correct dashboard for the user’s role.</p>
 */
public final class SceneManager {

    /**
     * Implemented by controllers backed by tables that should reload whenever the scene becomes visible again.
     */
    public interface Refreshable {
        void refresh();
    }

    /**
     * Catalog of FXML resources bundled inside the client JAR ({@code src/main/resources}).
     *
     * <p>Each constant knows its classpath string so navigation code cannot miss a leading slash.</p>
     */
    public enum View {
        LOGIN           ("/com/auction/client/fxml/login.fxml"),
        REGISTER        ("/com/auction/client/fxml/register.fxml"),
        AUCTION_LIST    ("/com/auction/client/fxml/auction_list.fxml"),
        AUCTION_DETAIL  ("/com/auction/client/fxml/auction_detail.fxml"),
        SELLER_DASHBOARD("/com/auction/client/fxml/seller_dashboard.fxml"),
        ADMIN_PANEL     ("/com/auction/client/fxml/admin_panel.fxml");

        public final String fxmlPath;
        View(String path) { this.fxmlPath = path; }
    }

    private static Stage primaryStage;

    private static final Map<View, Parent> cachedRoots       = new HashMap<>();
    private static final Map<View, Object> cachedControllers = new HashMap<>();

    private SceneManager() {}

    /**
     * Must be called once in ClientMain.start() before any switchTo() call.
     * Stores the main window used by every screen.
     */
    public static void init(Stage stage) { primaryStage = stage; }

    /** Returns the primary Stage (used by controllers that need to resize the window). */
    public static Stage getStage() { return primaryStage; }

    /**
     * Show the requested screen, loading and caching it on first use.
     *
     * @param view the screen to navigate to
     */
    public static void switchTo(View view) {
        try {
            Parent root       = cachedRoots.get(view);
            Object controller = cachedControllers.get(view);

            if (root == null) {
                // FXMLLoader reads an .fxml layout file and creates the matching JavaFX objects.
                // It also creates the controller named in the FXML file and connects @FXML fields to controls.
                FXMLLoader loader = new FXMLLoader(
                        SceneManager.class.getResource(view.fxmlPath));
                root = loader.load();
                controller = loader.getController();
                cachedRoots.put(view, root);
                cachedControllers.put(view, controller);
            }

            // Refresh data if this controller supports it.
            // This is normal Java interface checking, but it matters here because cached JavaFX screens
            // need fresh server data when the user navigates back to them.
            if (controller instanceof Refreshable r) r.refresh();

            Scene scene = primaryStage.getScene();
            if (scene == null) {
                // First screen: create the Scene and attach CSS.
                scene = new Scene(root);
                scene.getStylesheets().add(
                        SceneManager.class.getResource(
                                "/com/auction/client/css/style.css").toExternalForm());
                primaryStage.setScene(scene);
            } else {
                // Later screens only need to swap the root.
                scene.setRoot(root);
            }
            primaryStage.show();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load view: " + view, e);
        }
    }

    /**
     * Open an auction detail screen for a specific auction.
     * This view is not cached because each visit has a different auction id.
     *
     * @param auctionId the database id of the auction to display
     */
    public static void showAuctionDetail(long auctionId) {
        try {
            // Auction detail is not cached because each detail screen needs a different auction id.
            // FXMLLoader creates a fresh controller so currentAuctionId starts clean for this visit.
            FXMLLoader loader = new FXMLLoader(
                    SceneManager.class.getResource(View.AUCTION_DETAIL.fxmlPath));
            Parent root = loader.load();
            com.auction.client.controller.AuctionDetailController ctrl = loader.getController();
            ctrl.loadAuction(auctionId);

            Scene scene = primaryStage.getScene();
            if (scene == null) {
                scene = new Scene(root);
                scene.getStylesheets().add(
                        SceneManager.class.getResource(
                                "/com/auction/client/css/style.css").toExternalForm());
                primaryStage.setScene(scene);
            } else {
                scene.setRoot(root);
            }
            primaryStage.show();
        } catch (Exception e) {
            AlertUtil.error("Navigation Error",
                    "Failed to open the auction detail screen.\n\n" + e.getMessage());
        }
    }

    /**
     * Remove a cached view so it loads fresh next time.
     */
    public static void evict(View view) {
        cachedRoots.remove(view);
        cachedControllers.remove(view);
    }

    /** Clear all cached screens, usually after logout. */
    public static void evictAll() {
        cachedRoots.clear();
        cachedControllers.clear();
    }
}
