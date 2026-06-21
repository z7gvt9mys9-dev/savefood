package kz.savefood.partner.dto;

import java.time.LocalDate;

/** Port of partner_api.py {@code ApiLotIn} (POST /api/v1/lots). */
public record ApiLotIn(
    String description,
    Integer quantity,
    String category,
    LocalDate expiryDate,
    String address,
    String timeSlot,
    String comment
) {
}
