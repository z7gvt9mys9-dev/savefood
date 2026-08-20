package ru.savefood.security;

/**
 * Authenticated principal decoded from the JWT — the Java analogue of the Python
 * {@code current_user} dict ({@code sub}, {@code role}, {@code related_id}).
 * {@code userId} is the immutable database identity carried by the JWT subject;
 * {@code sub} remains the canonical username exposed to existing callers.
 */
public record CurrentUser(int userId, String sub, String role, Integer relatedId) {
    public boolean isAdmin() {
        return "admin".equals(role);
    }
}
