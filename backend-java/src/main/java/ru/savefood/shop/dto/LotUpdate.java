package ru.savefood.shop.dto;

import java.time.LocalDate;

/** PATCH /lots/{id} body (schemas.py LotUpdate) — every field optional. */
public record LotUpdate(
    String description,
    Double quantity,
    String unit,
    Double unitWeightKg,
    LocalDate expiryDate,
    String address,
    String category,
    String comment,
    Boolean requiresCold) {
}
