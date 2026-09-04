package ru.savefood.util;
import java.security.SecureRandom;
/** Port of utils.py {@code generate_join_code} — short team invite codes. */
public final class JoinCode {
    private JoinCode() {
    }
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int LENGTH = 6;
    private static final SecureRandom RNG = new SecureRandom();
    public static String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
