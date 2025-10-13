package com.harmony.flipper.util;

public final class NumberUtils {

    private NumberUtils() {}

    public static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
