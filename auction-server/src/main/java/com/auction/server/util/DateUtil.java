package com.auction.server.util;

import java.time.LocalDateTime; // the date-time type used throughout the server domain
import java.time.format.DateTimeFormatter;       // formats a date-time to a string
import java.time.format.DateTimeFormatterBuilder; // builder that lets us combine optional parts

/**
 * Normalises timestamp strings read from SQLite TEXT columns into {@link java.time.LocalDateTime}.
 *
 * <p>SQLite often stores {@code "yyyy-MM-dd HH:mm:ss"} while Java prefers {@code 'T'} between date and time.
 * {@link java.time.format.DateTimeFormatterBuilder} lets one formatter accept both shapes so every DAO can share it.</p>
 */
public final class DateUtil {

    private DateUtil() {} // utility class - no instances

    private static final DateTimeFormatter LENIENT = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)   // mandatory: yyyy-MM-dd
            .optionalStart().appendLiteral('T').optionalEnd() // optional T separator
            .optionalStart().appendLiteral(' ').optionalEnd() // optional space separator
            .append(DateTimeFormatter.ISO_LOCAL_TIME)   // mandatory: HH:mm[:ss[.nanos]]
            .toFormatter();

    public static LocalDateTime parse(String text) {
        if (text == null || text.isBlank()) return LocalDateTime.now();
        return LocalDateTime.parse(text.trim(), LENIENT);
    }
}
