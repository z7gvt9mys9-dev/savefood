package kz.savefood.volunteer.dto;

/**
 * Port of schemas.py {@code CompletePointRequest} — body for complete_point and
 * attempt_delivery. {@code ticketId} null targets the shop point.
 */
public record CompletePointRequest(
    Integer volunteerId,
    Integer ticketId,
    Double lat,
    Double lon,
    String qrCode
) {
}
