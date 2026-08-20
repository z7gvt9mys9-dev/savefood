package ru.savefood.admin;

/**
 * Body of the volunteer KYC moderation endpoint: {@code {"status": "approved"}} or
 * {@code {"status": "rejected"}}, with an optional free-text reason recorded in
 * the audit log (never shown to the applicant).
 */
public record ModerationDecision(
    String status,
    String reason
) {
}
