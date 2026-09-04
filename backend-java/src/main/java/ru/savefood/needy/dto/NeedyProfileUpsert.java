package ru.savefood.needy.dto;
public record NeedyProfileUpsert(
    String address,
    Integer familySize,
    String preferences,
    String urgency,
    String availableTime,
    String apartment,
    String floorNum,
    String entrance,
    String city,
    Double lat,
    Double lon,
    /** Explicitly clear a stale geocode when an address was edited manually. */
    Boolean clearCoordinates
) {
}
