package ru.savefood.volunteer.dto;

/** Port of schemas.py {@code VolunteerCreate} (POST /volunteers/register). */
public record VolunteerCreate(
    String name,
    String contact,
    Double lat,
    Double lon,
    String city,
    String username,
    String password
) {
}
