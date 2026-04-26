package com.auction.server.util;

import java.time.LocalDateTime; // the date-time type used throughout the server domain
import java.time.format.DateTimeFormatter;       // formats a date-time to a string
import java.time.format.DateTimeFormatterBuilder; // builder that lets us combine optional parts

/**
 * FILE ROLE: Defensive date-time parser that handles multiple timestamp formats.
 *
 * WHY THIS EXISTS:
 *   SQLite's strftime() and datetime() functions produce timestamps with a SPACE
 *   separator between date and time: "2026-04-22 14:32:07".
 *
 *   Java's LocalDateTime.toString() produces ISO-8601 with a 'T' separator:
 *   "2026-04-22T14:32:07" or "2026-04-22T14:32:07.123456789" (with nanoseconds).
 *
 *   If we use LocalDateTime.parse(str) directly on a SQLite-formatted string,
 *   it throws DateTimeParseException because the standard parser is strict about 'T'.
 *
 *   This class builds a lenient formatter that accepts BOTH forms, so every DAO
 *   can call DateUtil.parse(rs.getString("created_at")) without worrying about
 *   which format the database stored.
 *
 * USED BY:
 *   All five SQLite DAO implementations (SQLiteUserDAO, SQLiteItemDAO,
 *   SQLiteAuctionDAO, SQLiteBidDAO, SQLiteAutoBidDAO) when reading date columns.
 */
public final class DateUtil {

    private DateUtil() {} // utility class — no instances

    /**
     * A DateTimeFormatter that accepts both:
     *   - "2026-04-22T14:32:07"              (Java ISO-8601, T separator)
     *   - "2026-04-22T14:32:07.123456789"    (Java ISO-8601 with nanoseconds)
     *   - "2026-04-22 14:32:07"              (SQLite space separator)
     *
     * HOW IT WORKS:
     *   DateTimeFormatterBuilder lets us compose a formatter from pieces.
     *   'optionalStart/End' means "try to match this, but don't fail if absent".
     *   We make both 'T' and ' ' optional, then use ISO_LOCAL_TIME which handles
     *   both "HH:mm:ss" and "HH:mm:ss.nnnnnnnnn".
     */
    private static final DateTimeFormatter LENIENT = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)   // mandatory: yyyy-MM-dd
            .optionalStart().appendLiteral('T').optionalEnd() // optional T separator
            .optionalStart().appendLiteral(' ').optionalEnd() // optional space separator
            .append(DateTimeFormatter.ISO_LOCAL_TIME)   // mandatory: HH:mm[:ss[.nanos]]
            .toFormatter();

    /**
     * Parse a date-time string from the database into a LocalDateTime.
     *
     * If the string is null or blank (shouldn't happen, but defensive), returns
     * LocalDateTime.now() rather than throwing, so the DAO doesn't crash on bad data.
     *
     * @param text a date-time string from an SQLite TEXT column
     * @return the parsed LocalDateTime
     */
    public static LocalDateTime parse(String text) {
        if (text == null || text.isBlank()) return LocalDateTime.now();
        return LocalDateTime.parse(text.trim(), LENIENT);
    }
}
