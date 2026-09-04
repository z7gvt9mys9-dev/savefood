package ru.savefood.util;
import java.security.SecureRandom;
import java.util.Base64;
/** Per-ticket QR helpers, ported from utils.py {@code generate_qr_secret} / {@code build_qr_code}. */
public final class Qr {
    private Qr() {
    }
    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder URLSAFE = Base64.getUrlEncoder().withoutPadding();
    public static String generateSecret() {
        byte[] bytes = new byte[8];
        RNG.nextBytes(bytes);
        return URLSAFE.encodeToString(bytes);
    }
    public static String buildCode(int ticketId, String secret) {
        return secret != null && !secret.isEmpty() ? "SF-" + ticketId + "-" + secret : "SF-" + ticketId;
    }
}
