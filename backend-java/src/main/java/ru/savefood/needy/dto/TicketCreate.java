package ru.savefood.needy.dto;

/** Port of schemas.py {@code TicketCreate}. */
public record TicketCreate(
    String items,
    String address,
    Double lat,
    Double lon,
    String availableTime,
    Integer lotId,
    String apartment,
    String floorNum,
    String entrance,
    Boolean selfPickup
) {
}
