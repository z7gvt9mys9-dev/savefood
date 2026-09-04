package ru.savefood.volunteer.dto;
public record CompletePointRequest(
    Integer volunteerId,
    Integer ticketId,
    Double lat,
    Double lon,
    String qrCode
) {
}
