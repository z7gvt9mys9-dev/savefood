package ru.savefood.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the real client address behind the reverse proxy.
 *
 * <p>{@code request.getRemoteAddr()} is useless here: every request arrives from
 * the nginx container, so keying a rate limiter on it gives ONE shared bucket for
 * the whole world — a single user (or attacker) exhausts the quota for everybody.
 *
 * <p>Resolution order, most trustworthy first:
 * <ol>
 *   <li>{@code X-Real-IP} — set by our own nginx to {@code $client_real_ip}, which
 *       already resolves to {@code CF-Connecting-IP} behind the Cloudflare Tunnel
 *       and falls back to {@code $remote_addr} for direct access. Same value the
 *       nginx {@code limit_req} zone keys on, so both layers throttle the same
 *       client.</li>
 *   <li>{@code CF-Connecting-IP} — if Cloudflare ever reaches the app without our
 *       nginx in front.</li>
 *   <li>{@code X-Forwarded-For} — first entry (the original client); the rest are
 *       intermediate proxies.</li>
 *   <li>{@code getRemoteAddr()} — direct connection, e.g. local dev.</li>
 * </ol>
 *
 * <p>These headers are client-supplied and therefore spoofable in principle. That
 * is acceptable only because nothing but rate limiting reads them, and nginx
 * overwrites {@code X-Real-IP} on every proxied location — a forged header from
 * the outside never survives the hop. Never use this for authorization.
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        if (request == null) {
            return "?";
        }
        String realIp = firstNonBlank(request.getHeader("X-Real-IP"), request.getHeader("CF-Connecting-IP"));
        if (realIp != null) {
            return realIp;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // "client, proxy1, proxy2" — the leftmost entry is the origin.
            String first = forwarded.split(",", 2)[0].strip();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "?" : remote;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.strip();
        }
        return b != null && !b.isBlank() ? b.strip() : null;
    }
}
