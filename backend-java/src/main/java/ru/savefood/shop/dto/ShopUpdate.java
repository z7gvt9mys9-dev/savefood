package ru.savefood.shop.dto;

/** PATCH /shops/{id} body (schemas.py ShopUpdate). */
public record ShopUpdate(
    String name,
    String contact,
    Double lat,
    Double lon,
    String city) {
}
