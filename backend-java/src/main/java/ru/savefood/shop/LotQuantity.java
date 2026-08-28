package ru.savefood.shop;

import ru.savefood.web.ApiException;

/** Validation for the discrete units consumed by one recipient reservation. */
public final class LotQuantity {

    private LotQuantity() { }

    public static void requireWholeUnits(Double value, String field) {
        if (value == null || !Double.isFinite(value) || value < 1 || value != Math.rint(value)) {
            throw new ApiException(422, field + ": должно быть целым числом не меньше 1");
        }
    }

    public static void requireWholeUnits(double value, String field) {
        requireWholeUnits(Double.valueOf(value), field);
    }
}
