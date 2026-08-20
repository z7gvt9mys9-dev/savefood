package ru.savefood.volunteer.dto;

/** Admin decision for a volunteer identity document: {@code approved} or {@code rejected}. */
public record ModerationUpdate(
    String status
) {
}
