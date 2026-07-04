package ru.savefood.security;

/**
 * Authenticated principal decoded from the JWT — the Java analogue of the Python
 * {@code current_user} dict ({@code sub}, {@code role}, {@code related_id}).
 */
public record CurrentUser(String sub, String role, Integer relatedId) {
    public boolean isAdmin() {
        return "admin".equals(role);
    }
}
