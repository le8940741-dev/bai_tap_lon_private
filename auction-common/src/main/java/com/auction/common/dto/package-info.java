/**
 * Shared “wire format” beans for the auction TCP protocol.
 *
 * <p>They live in {@code auction-common} so the server and client JARs depend on the same
 * class names and field shapes. Gson uses reflection on these simple POJOs — notice how
 * they mirror what you see in SQLite columns, but deliberately omit secrets such as password hashes.</p>
 */
package com.auction.common.dto;
