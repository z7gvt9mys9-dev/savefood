package kz.savefood.needy.dto;

/**
 * Port of schemas.py {@code NeedyProfileCreate} / {@code NeedyProfileUpdate} —
 * field-identical, so one record backs both the POST and PATCH profile routes.
 */
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
    Double lon
) {
}
