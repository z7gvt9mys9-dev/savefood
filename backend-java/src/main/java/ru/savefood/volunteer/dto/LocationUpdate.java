package ru.savefood.volunteer.dto;

/** Port of schemas.py {@code LocationUpdate} (PATCH /volunteers/{id}/location). */
public record LocationUpdate(Double lat, Double lon) {
}
