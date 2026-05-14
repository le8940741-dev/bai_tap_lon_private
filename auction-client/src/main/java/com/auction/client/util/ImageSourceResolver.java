package com.auction.client.util;

import java.nio.file.Path;
import java.util.List;

/**
 * Converts user-entered image strings into JavaFX Image-compatible URLs.
 *
 * <p>Runtime flow: AuctionDetailController calls this while rendering an item image after auction
 * details arrive from the server; the utility is stateless and lives only as static methods.</p>
 *
 * <p>Created/called by: no object creates this class; controllers call it directly, and it calls
 * JDK Path/URI APIs when a plain local filesystem path needs conversion.</p>
 */
public final class ImageSourceResolver {

    private static final List<String> URL_SCHEMES = List.of(
            "http://",
            "https://",
            "file:/",
            "jar:",
            "data:");

    private ImageSourceResolver() {}

    /**
     * Return a JavaFX Image source string, or {@code null} when the input cannot be used.
     */
    public static String toImageSource(String rawSource) {
        if (rawSource == null || rawSource.isBlank()) {
            return null;
        }

        String trimmed = rawSource.trim();
        String lower = trimmed.toLowerCase();
        if (URL_SCHEMES.stream().anyMatch(lower::startsWith)) {
            return trimmed;
        }

        try {
            // Path.toUri(): turns a local path into the file:/ URL shape JavaFX Image expects.
            return Path.of(trimmed).toAbsolutePath().toUri().toString();
        } catch (Exception ignored) {
            return null;
        }
    }
}
