package com.auction.client.util;

import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;

import java.util.Optional;

/**
 * Thin façade around JavaFX {@link Alert} so controllers do not repeat boilerplate styling.
 *
 * <p>Alerts are always modal ({@code showAndWait}) so the student sees a clear stop in the flow
 * when validation or the server reports an error.</p>
 */
public final class AlertUtil {

    private AlertUtil() {}

    /**
     * Show a modal error dialog.
     * Use this for server errors, validation failures, and connection problems.
     *
     * @param title   the dialog window title bar text
     * @param message the error description shown in the body
     */
    public static void error(String title, String message) {
        Alert alert = new Alert(AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    /**
     * Show a modal information dialog.
     * Use this for success messages.
     */
    public static void info(String title, String message) {
        Alert alert = new Alert(AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    /**
     * Show a modal confirmation dialog with OK and Cancel buttons.
     * Use this before destructive actions such as canceling an auction.
     *
     * @return true if the user clicked OK; false for Cancel or window-close
     */
    public static boolean confirm(String title, String message) {
        Alert alert = new Alert(AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        Optional<ButtonType> result = alert.showAndWait();
        // Closing the dialog without choosing a button counts as cancel.
        return result.isPresent() && result.get() == ButtonType.OK;
    }
}
