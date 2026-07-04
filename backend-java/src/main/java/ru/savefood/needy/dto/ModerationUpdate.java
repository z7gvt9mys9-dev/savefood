package ru.savefood.needy.dto;

/** Admin moderation decision for a needy/volunteer KYC document: {@code approved} | {@code rejected}. */
public record ModerationUpdate(
    String status
) {
}
