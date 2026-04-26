package com.auction.client.util;

// Alert is JavaFX's built-in dialog for notifications and confirmations.
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType; // ERROR, INFORMATION, CONFIRMATION
import javafx.scene.control.ButtonType;       // OK, CANCEL — what the user clicked

import java.util.Optional; // wraps the user's button choice (may have closed with X)

/**
 * FILE ROLE: Convenience wrapper for JavaFX dialog boxes.
 *
 * Without this utility, every controller would repeat:
 *   Alert a = new Alert(AlertType.ERROR);
 *   a.setTitle(...); a.setHeaderText(null); a.setContentText(...); a.showAndWait();
 *
 * AlertUtil reduces that to: AlertUtil.error("Title", "Message")
 *
 * showAndWait() blocks the FX thread until the user dismisses the dialog —
 * this is intentional for errors (the user must acknowledge) and confirmations
 * (we need the answer before proceeding).
 *
 * USED BY: All controllers for error display and cancel confirmations.
 */
public final class AlertUtil {

    private AlertUtil() {} // utility class — no instances

    /**
     * Show a modal error dialog.
     * Use for: server errors, validation failures, connection problems.
     *
     * @param title   the dialog window title bar text
     * @param message the error description shown in the body
     */
    public static void error(String title, String message) {
        Alert alert = new Alert(AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);   // no secondary title — keep it simple
        alert.setContentText(message);
        alert.showAndWait();         // blocks until user clicks OK
    }

    /**
     * Show a modal information dialog.
     * Use for: success confirmations (e.g. "Account created!").
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
     * Use for: destructive actions (cancel auction, ban user).
     *
     * @return true if the user clicked OK; false for Cancel or window-close
     */
    public static boolean confirm(String title, String message) {
        Alert alert = new Alert(AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        Optional<ButtonType> result = alert.showAndWait();
        // isPresent() is false if the user closed the dialog without clicking a button.
        return result.isPresent() && result.get() == ButtonType.OK;
    }
}
