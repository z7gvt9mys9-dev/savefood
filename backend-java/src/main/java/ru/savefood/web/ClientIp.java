package ru.savefood.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the real client address behind the reverse proxy.
 *
 * <p>{@code request.getRemoteAddr()} is useless here: every request arrives from
 * the nginx container, so keying a rate limiter on it gives ONE shared bucket for
 * the whole world — a single user (or attacker) exhausts the quota for everybody.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code X-Real-IP} — set by our own nginx to {@code $client_real_ip}, which
 *       validates a Cloudflare address only on its loopback-only tunnel hop. Same
 *       value the nginx {@code limit_req} zone keys on.</li>
 *   <li>{@code getRemoteAddr()} — direct local development.</li>
 * </ol>
 *
 * <p>Never fall back to {@code CF-Connecting-IP} or {@code X-Forwarded-For} here:
 * callers that bypass nginx can forge both and evade application-level limits.
 * Never use this value for authorization.
 */
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
