package ru.savefood.volunteer.dto;

import java.util.List;

/** Port of schemas.py {@code VolunteerUpdate} (PATCH /volunteers/{id}). */
public record VolunteerUpdate(
    String name,
    String contact,
    Double lat,
    Double lon,
    String city,
    Boolean hasThermalBag,
    List<AvailabilityWindow> availability
) {
}
