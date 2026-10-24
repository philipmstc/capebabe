package net.shchoo.scafe.scafe.client.ui;

/**
 * Big counts in a few characters, rounded down so they never claim more than there is: 999, 1.1k, 10.2k, 123k,
 * 1.2M, 10.2M, 123M, then B and T the same way.
 */
public final class ShortNumbers {
    private static final String UNITS = "kMBT";

    private ShortNumbers() {}

    public static String of(long n) {
        if (n < 1000) {
            return String.valueOf(n);
        }
        long scale = 1;
        int unit = -1;
        while (unit < UNITS.length() - 1 && n >= scale * 1000) {
            scale *= 1000;
            unit++;
        }
        long tenths = n / (scale / 10 == 0 ? 1 : scale / 10);
        String digits = tenths >= 1000 ? String.valueOf(tenths / 10) : tenths / 10 + "." + tenths % 10;
        return digits + UNITS.charAt(unit);
    }
}
