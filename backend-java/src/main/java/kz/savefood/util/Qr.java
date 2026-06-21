package kz.savefood.util;

import java.security.SecureRandom;
import java.util.Base64;

/** Per-ticket QR helpers, ported from utils.py {@code generate_qr_secret} / {@code build_qr_code}. */
public final class Qr {
    private Qr() {
    }

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder URLSAFE = Base64.getUrlEncoder().withoutPadding();

    /**
     * Per-ticket secret baked into the delivery QR ({@code SF-{id}-{secret}}). Not
     * derivable from the ticket id — ~8 random bytes ≈ 64 bits, url-safe encoded —
     * so a volunteer can't mark a delivery fulfilled (nor a shop close a
     * self-pickup) without the recipient physically presenting the code.
     */
    public static String generateSecret() {
        byte[] bytes = new byte[8];
        RNG.nextBytes(bytes);
        return URLSAFE.encodeToString(bytes);
    }

    /**
     * Canonical QR payload for a ticket. Falls back to the legacy secret-less form
     * for any pre-migration ticket whose secret was never set.
     */
    public static String buildCode(int ticketId, String secret) {
        return secret != null && !secret.isEmpty() ? "SF-" + ticketId + "-" + secret : "SF-" + ticketId;
    }
}
