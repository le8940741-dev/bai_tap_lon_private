package com.auction.server.util;

// Import all DTO types from the shared module - these are what we produce.
import com.auction.common.dto.AuctionDTO;
import com.auction.common.dto.BidDTO;
import com.auction.common.dto.ItemDTO;
import com.auction.common.dto.UserDTO;

// Import all server-side domain models - these are what we convert FROM.
import com.auction.server.model.*;

import java.time.ZoneId;                        // converts LocalDateTime to ZonedDateTime for epoch millis
import java.time.format.DateTimeFormatter;       // formats LocalDateTime to ISO-8601 string

/**
 * Static translators from rich <b>domain model</b> objects to slim <b>DTOs</b> for Gson.
 *
 * <p>Keeping conversion here avoids duplicating field-copy logic inside every
 * {@link com.auction.server.network.ClientHandler} handler and documents which domain fields are
 * considered safe to expose over TCP.</p>
 */
public final class DtoMapper {

    private DtoMapper() {} // utility class - no instances

    // ISO-8601 formatter: "2026-04-22T14:32:07" - matches what DateUtil.parse() accepts.
    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // User -> UserDTO

    public static UserDTO toDto(User u) {
        return new UserDTO(u.getId(), u.getUsername(), u.getEmail(),
                u.getRole().name(), // enum -> string: "BIDDER", "SELLER", "ADMIN"
                u.isActive());
    }

    // Item -> ItemDTO

    public static ItemDTO toDto(Item item) {
        ItemDTO dto = new ItemDTO();
        dto.setId(item.getId());
        dto.setName(item.getName());
        dto.setDescription(item.getDescription());
        dto.setCategory(item.getCategory().name()); // enum -> string
        dto.setSellerId(item.getSellerId());
        dto.setSellerName(item.getSellerName());
        dto.setImageUrl(item.getImageUrl());
        dto.setExtraData(item.getExtraData());
        if (item.getCreatedAt() != null) dto.setCreatedAt(FMT.format(item.getCreatedAt()));
        return dto;
    }

    // Auction -> AuctionDTO

    public static AuctionDTO toDto(Auction a) {
        AuctionDTO dto = new AuctionDTO();
        dto.setId(a.getId());
        dto.setItem(a.getItem() != null ? toDto(a.getItem()) : null); // recursive conversion
        dto.setStartingPrice(a.getStartingPrice());
        dto.setCurrentPrice(a.getCurrentPrice());
        dto.setStartTime(FMT.format(a.getStartTime()));
        dto.setEndTime(FMT.format(a.getEndTime()));
        dto.setStatus(a.getStatus().name()); // enum -> string
        dto.setSellerId(a.getSellerId());
        dto.setSellerName(a.getSellerName());
        dto.setWinnerId(a.getLeadingBidderId());       // null if no bids yet
        dto.setWinnerName(a.getLeadingBidderName());   // null if no bids yet
        if (a.getCreatedAt() != null) dto.setCreatedAt(FMT.format(a.getCreatedAt()));
        return dto;
    }

    // BidTransaction -> BidDTO

    public static BidDTO toDto(BidTransaction bt) {
        BidDTO dto = new BidDTO();
        dto.setId(bt.getId());
        dto.setAuctionId(bt.getAuctionId());
        dto.setBidderId(bt.getBidderId());
        dto.setBidderName(bt.getBidderName());
        dto.setAmount(bt.getAmount());
        if (bt.getTimestamp() != null) {
            dto.setTimestamp(FMT.format(bt.getTimestamp())); // human-readable string
            // Convert LocalDateTime -> ZonedDateTime -> Instant -> epoch millis.
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
