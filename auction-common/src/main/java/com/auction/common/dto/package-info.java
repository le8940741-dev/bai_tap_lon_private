/**
 * FILE ROLE: Package containing all Data Transfer Objects (DTOs) shared between
 * the server module (auction-server) and the client module (auction-client).
 *
 * WHY A SHARED DTO PACKAGE:
 *   The server's domain model (User, Item, Auction, etc.) lives in auction-server
 *   and the client must never depend on auction-server directly — that would pull
 *   in JDBC, SLF4J, and all server dependencies into the client JAR.
 *
 *   DTOs solve this: they are plain Java classes with no dependencies beyond Gson,
 *   which both modules already use. The server converts domain objects → DTOs
 *   (via DtoMapper) before serialising to JSON. The client deserialises JSON → DTOs
 *   and reads them directly.
 *
 * WHY PLAIN CLASSES (not Java records):
 *   Gson requires a no-argument constructor for deserialisation. Java records
 *   do not have one by default, which would force us to write a custom TypeAdapter
 *   for every DTO. Plain classes with a no-arg constructor work out of the box.
 *
 * WHY PUBLIC FIELDS ARE NOT USED HERE (unlike Requests.java):
 *   DTOs have more validation surface — they're read both ways (server writes,
 *   client reads; client state, server reads). Private fields + getters make
 *   the contract explicit and allow future validation without breaking callers.
 *
 * CLASSES IN THIS PACKAGE:
 *   UserDTO    — user account without passwordHash (never sent over the wire)
 *   ItemDTO    — auction item with category string and optional extraData JSON blob
 *   AuctionDTO — full auction state including embedded ItemDTO
 *   BidDTO     — single bid with dual timestamp (ISO string for display, millis for chart)
 */
package com.auction.common.dto;
