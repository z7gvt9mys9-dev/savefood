package ru.savefood.needy.dto;

/**
 * Port of schemas.py {@code NeedyCreate}: the registration body, also reused as
 * the {@code PATCH /needy/{id}} payload (where only name/contact are applied).
 */
public record NeedyCreate(
    String name,
    String contact,
    String username,
    String password
) {
}
