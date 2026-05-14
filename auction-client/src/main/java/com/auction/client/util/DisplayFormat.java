package com.auction.client.util;

/**
 * Formats server wire values for labels and table cells in JavaFX controllers.
 *
 * <p>Runtime flow: controllers call this utility while populating views after a server response;
 * the class has no state and lives for the lifetime of the client JVM through static methods.</p>
 *
 * <p>Created/called by: no object creates this class; JavaFX controllers call it directly, and it
 * calls only JDK string methods so formatting stays independent from network and FXML code.</p>
 */
public final class DisplayFormat {

    private DisplayFormat() {}

    /**
     * Converts an ISO-8601 timestamp such as {@code 2026-05-14T10:15:30} to minute precision.
     */
    public static String isoToMinuteLabel(String iso) {
        if (iso == null) {
            return "";
        }
        return iso.replace("T", " ").substring(0, Math.min(16, iso.length()));
    }
}
