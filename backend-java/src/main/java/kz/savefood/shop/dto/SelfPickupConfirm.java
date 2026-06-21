package kz.savefood.shop.dto;

/** POST /shops/{id}/self_pickup/confirm body (schemas.py SelfPickupConfirm). */
public record SelfPickupConfirm(String code) {
}
