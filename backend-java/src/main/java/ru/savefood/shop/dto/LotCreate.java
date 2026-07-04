package ru.savefood.shop.dto;

import java.time.LocalDate;

/** POST /shops/{id}/lots JSON body (schemas.py LotCreate). */
public record LotCreate(
    String description,
    Double quantity,
    String unit,
    Double unitWeightKg,
    LocalDate expiryDate,
    String photo,
    String address,
    String timeSlot,
    String category,
    String comment,
    Boolean requiresCold) {
}
