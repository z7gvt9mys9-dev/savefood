package kz.savefood.shop.dto;

/** POST /shops/register body (schemas.py ShopCreate). */
public record ShopCreate(
    String name,
    String contact,
    Double lat,
    Double lon,
    String city,
    String username,
    String password,
    String kind) {
}
