package ru.savefood.web;
import jakarta.servlet.http.HttpServletRequest;
public final class ClientIp {
    private ClientIp() {
    }
    public static String of(HttpServletRequest request) {
        if (request == null) {
            return "?";
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null) {
            String normalized = realIp.strip();
            if (!normalized.isEmpty()) return normalized;
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "?" : remote;
    }
}
