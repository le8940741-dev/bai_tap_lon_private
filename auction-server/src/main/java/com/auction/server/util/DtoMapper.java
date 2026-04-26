package com.auction.server.util;

// Import all DTO types from the shared module — these are what we produce.
import com.auction.common.dto.AuctionDTO;
import com.auction.common.dto.BidDTO;
import com.auction.common.dto.ItemDTO;
import com.auction.common.dto.UserDTO;

// Import all server-side domain models — these are what we convert FROM.
import com.auction.server.model.*;

import java.time.ZoneId;                        // converts LocalDateTime to ZonedDateTime for epoch millis
import java.time.format.DateTimeFormatter;       // formats LocalDateTime to ISO-8601 string

/**
 * FILE ROLE: Converts server-side domain objects into wire-safe DTOs.
 *
 * WHY A SEPARATE MAPPER:
 *   Domain models (User, Item, Auction, BidTransaction) contain server-only
 *   fields (passwordHash, complex object references) and Java types
 *   (LocalDateTime, enums) that Gson can serialise but the client doesn't need.
 *
 *   By converting to DTOs here, we keep three concerns cleanly separated:
 *     1. Domain model — business logic, validation, relationships.
 *     2. DtoMapper    — the translation layer (Single Responsibility Principle).
 *     3. DTO          — the wire contract the client depends on.
 *
 *   If you add a field to the domain model, only DtoMapper needs updating;
 *   the DTO and client can stay unchanged (Open/Closed Principle).
 *
 * USED BY:
 *   ClientHandler — calls DtoMapper.toDto(user/item/auction/bid) before
 *   building every response message.
 */
public final class DtoMapper {

    private DtoMapper() {} // utility class — no instances

    // ISO-8601 formatter: "2026-04-22T14:32:07" — matches what DateUtil.parse() accepts.
    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // ── User → UserDTO ────────────────────────────────────────────────────────

    /**
     * Convert a domain User to a wire-safe UserDTO.
     * Deliberately omits 'passwordHash' — that field never crosses the network.
     *
     * @param u any User subclass (Bidder, Seller, Admin)
     * @return a UserDTO safe to send over the wire
     */
    public static UserDTO toDto(User u) {
        return new UserDTO(u.getId(), u.getUsername(), u.getEmail(),
                u.getRole().name(), // enum → string: "BIDDER", "SELLER", "ADMIN"
                u.isActive());
    }

    // ── Item → ItemDTO ────────────────────────────────────────────────────────

    /**
     * Convert a domain Item (Electronics/Art/Vehicle) to an ItemDTO.
     * getCategory() is polymorphic — resolves to the correct subclass at runtime.
     *
     * @param item any Item subclass
     * @return an ItemDTO carrying name, description, category, seller info, extraData
     */
    public static ItemDTO toDto(Item item) {
        ItemDTO dto = new ItemDTO();
        dto.setId(item.getId());
        dto.setName(item.getName());
        dto.setDescription(item.getDescription());
        dto.setCategory(item.getCategory().name()); // enum → string
        dto.setSellerId(item.getSellerId());
        dto.setSellerName(item.getSellerName());
        dto.setExtraData(item.getExtraData());
        if (item.getCreatedAt() != null) dto.setCreatedAt(FMT.format(item.getCreatedAt()));
        return dto;
    }

    // ── Auction → AuctionDTO ──────────────────────────────────────────────────

    /**
     * Convert a domain Auction to an AuctionDTO.
     * The embedded item is also converted to an ItemDTO so the client
     * can display item details without a second round-trip.
     *
     * @param a the auction to convert
     * @return an AuctionDTO with a nested ItemDTO
     */
    public static AuctionDTO toDto(Auction a) {
        AuctionDTO dto = new AuctionDTO();
        dto.setId(a.getId());
        dto.setItem(a.getItem() != null ? toDto(a.getItem()) : null); // recursive conversion
        dto.setStartingPrice(a.getStartingPrice());
        dto.setCurrentPrice(a.getCurrentPrice());
        dto.setStartTime(FMT.format(a.getStartTime()));
        dto.setEndTime(FMT.format(a.getEndTime()));
        dto.setStatus(a.getStatus().name()); // enum → string
        dto.setSellerId(a.getSellerId());
        dto.setSellerName(a.getSellerName());
        dto.setWinnerId(a.getLeadingBidderId());       // null if no bids yet
        dto.setWinnerName(a.getLeadingBidderName());   // null if no bids yet
        if (a.getCreatedAt() != null) dto.setCreatedAt(FMT.format(a.getCreatedAt()));
        return dto;
    }

    // ── BidTransaction → BidDTO ───────────────────────────────────────────────

    /**
     * Convert a domain BidTransaction to a BidDTO.
     * Computes the epoch-millisecond timestamp here so the client's LineChart
     * gets a numeric X-axis value directly.
     *
     * @param bt the bid transaction record
     * @return a BidDTO with both ISO-8601 string and epoch-millis timestamps
     */
    public static BidDTO toDto(BidTransaction bt) {
        BidDTO dto = new BidDTO();
        dto.setId(bt.getId());
        dto.setAuctionId(bt.getAuctionId());
        dto.setBidderId(bt.getBidderId());
        dto.setBidderName(bt.getBidderName());
        dto.setAmount(bt.getAmount());
        if (bt.getTimestamp() != null) {
            dto.setTimestamp(FMT.format(bt.getTimestamp())); // human-readable string
            // Convert LocalDateTime → ZonedDateTime → Instant → epoch millis.
            // ZoneId.systemDefault() uses the server's local timezone.
            dto.setTimestampMillis(
                    bt.getTimestamp()
                      .atZone(ZoneId.systemDefault())
                      .toInstant()
                      .toEpochMilli());
        }
        dto.setAutoBid(bt.isAutoBid());
        return dto;
    }
}
