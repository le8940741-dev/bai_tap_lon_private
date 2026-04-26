package com.auction.client.util;

// FXMLLoader parses .fxml XML files and instantiates the declared JavaFX scene graph.
import javafx.fxml.FXMLLoader;

// Parent is the base class for all JavaFX scene-graph root nodes.
import javafx.scene.Parent;

// Scene is the container for the root node; exactly one Scene per Stage.
import javafx.scene.Scene;

// Stage is the OS window — the top-level container managed by JavaFX.
import javafx.stage.Stage;

import java.io.IOException;
import java.util.HashMap;  // cache for loaded FXML roots
import java.util.Map;      // Map interface for the cache

/**
 * FILE ROLE: Centralised scene (screen) switcher for the JavaFX client.
 *
 * PROBLEM IT SOLVES:
 *   Switching between screens in JavaFX requires:
 *     1. Loading the FXML file (slow — parses XML and instantiates nodes).
 *     2. Getting the controller from the loader.
 *     3. Swapping the root node on the existing Scene.
 *   Without a manager, every controller would repeat this boilerplate and
 *   either reload FXML every time (slow) or manage its own caches (messy).
 *
 * CACHING:
 *   Once a View is loaded, its root Parent and controller are cached in maps.
 *   Navigating back to an already-visited screen reuses the cached objects —
 *   no XML reparsing.  Exception: AUCTION_DETAIL is never cached because it
 *   receives an auctionId parameter that varies per visit.
 *
 * REFRESHABLE INTERFACE:
 *   Controllers that need to reload data on every visit (AuctionListController,
 *   SellerDashboardController, AdminController) implement Refreshable.
 *   switchTo() calls refresh() after setting the root, so the table data is
 *   always current when navigating to a screen, even from the cache.
 *
 * CSS:
 *   The stylesheet is attached to the Scene once (at first load).
 *   All subsequent root swaps inherit the stylesheet automatically because CSS
 *   is attached to the Scene, not to individual root nodes.
 *
 * USED BY: All controllers (to navigate to another screen) and ClientMain (to set up initial screen).
 */
public final class SceneManager {

    /**
     * Marker interface for controllers that need to refresh data on every visit.
     * SceneManager calls refresh() after every switchTo() if the controller implements this.
     */
    public interface Refreshable {
        void refresh();
    }

    /**
     * Enum of all screens in the application.
     * Each constant holds the classpath-relative path to its FXML file.
     * Adding a new screen: add a constant here + create the FXML + create the controller.
     */
    public enum View {
        LOGIN           ("/com/auction/client/fxml/login.fxml"),
        REGISTER        ("/com/auction/client/fxml/register.fxml"),
        AUCTION_LIST    ("/com/auction/client/fxml/auction_list.fxml"),
        AUCTION_DETAIL  ("/com/auction/client/fxml/auction_detail.fxml"),
        SELLER_DASHBOARD("/com/auction/client/fxml/seller_dashboard.fxml"),
        ADMIN_PANEL     ("/com/auction/client/fxml/admin_panel.fxml");

        public final String fxmlPath; // the resource path passed to FXMLLoader
        View(String path) { this.fxmlPath = path; }
    }

    private static Stage primaryStage; // the single OS window — set in ClientMain.init()

    // Cache: View → loaded root node (avoiding re-parsing FXML on every navigation).
    private static final Map<View, Parent> cachedRoots       = new HashMap<>();

    // Cache: View → controller instance (for calling refresh()).
    private static final Map<View, Object> cachedControllers = new HashMap<>();

    private SceneManager() {} // utility class — no instances

    /** Must be called once in ClientMain.start() before any switchTo() call. */
    public static void init(Stage stage) { primaryStage = stage; }

    /** Returns the primary Stage (used by controllers that need to resize the window). */
    public static Stage getStage() { return primaryStage; }

    /**
     * Switch the displayed screen to the given View.
     *
     * If the View has been loaded before:
     *   - Retrieve root and controller from cache.
     * If not:
     *   - Load the FXML, cache the root and controller.
     * Then:
     *   - If the controller implements Refreshable, call refresh() to reload data.
     *   - Swap the Scene's root node (or create a new Scene if this is the first view).
     *   - Attach the CSS stylesheet on first Scene creation.
     *
     * @param view the screen to navigate to
     */
    public static void switchTo(View view) {
        try {
            Parent root       = cachedRoots.get(view);
            Object controller = cachedControllers.get(view);

            if (root == null) {
                // First visit — load from FXML and cache.
                FXMLLoader loader = new FXMLLoader(
                        SceneManager.class.getResource(view.fxmlPath));
                root = loader.load();
                controller = loader.getController();
                cachedRoots.put(view, root);
                cachedControllers.put(view, controller);
            }

            // Refresh data if the controller supports it.
            if (controller instanceof Refreshable r) r.refresh();

            Scene scene = primaryStage.getScene();
            if (scene == null) {
                // First screen — create the Scene and attach CSS.
                scene = new Scene(root);
                scene.getStylesheets().add(
                        SceneManager.class.getResource(
                                "/com/auction/client/css/style.css").toExternalForm());
                primaryStage.setScene(scene);
            } else {
                // Subsequent screens — just swap the root; CSS stays attached.
                scene.setRoot(root);
            }
            primaryStage.show();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load view: " + view, e);
        }
    }

    /**
     * Special navigation method for the auction detail screen.
     *
     * AUCTION_DETAIL is never cached because each visit is for a different
     * auction (different auctionId parameter).  A fresh FXMLLoader + controller
     * is created every time, and loadAuction(id) is called on the controller
     * to trigger data loading and server subscription.
     *
     * @param auctionId the database id of the auction to display
     */
    public static void showAuctionDetail(long auctionId) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    SceneManager.class.getResource(View.AUCTION_DETAIL.fxmlPath));
            Parent root = loader.load();
            com.auction.client.controller.AuctionDetailController ctrl = loader.getController();
            ctrl.loadAuction(auctionId); // trigger data load + WATCH_AUCTION subscription

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
        } catch (IOException e) {
            throw new RuntimeException("Failed to load auction detail view", e);
        }
    }

    /**
     * Remove a cached View so it is reloaded fresh on the next switchTo().
     * Called after logout to prevent stale user data from showing
     * if a different user logs in on the same client session.
     */
    public static void evict(View view) {
        cachedRoots.remove(view);
        cachedControllers.remove(view);
    }

    /** Evict all cached views (called on logout). */
    public static void evictAll() {
        cachedRoots.clear();
        cachedControllers.clear();
    }
}
