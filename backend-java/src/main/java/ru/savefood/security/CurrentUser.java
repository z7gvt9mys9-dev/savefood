package ru.savefood.security;
public record CurrentUser(int userId, String sub, String role, Integer relatedId) {
    public boolean isAdmin() {
        return "admin".equals(role);
    }
}
