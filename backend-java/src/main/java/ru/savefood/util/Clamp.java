package ru.savefood.util;
/** Port of backend/utils.py clamp: constrain a value to the inclusive [lo, hi]. */
public final class Clamp {
    private Clamp() {
    }
    public static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
