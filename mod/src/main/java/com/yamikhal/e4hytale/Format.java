package com.yamikhal.e4hytale;

import java.util.Locale;

// short human readable sizes and durations for the status texts
final class Format {

    private Format() {}

    // 950 B, 12.3 kB, 133 MB, 1.20 GB
    static String bytes(long bytes) {
        if (bytes < 1000) {
            return bytes + " B";
        }

        String[] units = {"kB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1000 && unit < units.length - 1) {
            value /= 1000;
            unit++;
        }

        String pattern = value >= 100 ? "%.0f %s" : value >= 10 ? "%.1f %s" : "%.2f %s";
        return String.format(Locale.ROOT, pattern, value, units[unit]);
    }

    // 45s, 12m, 1h 05m, 3d 4h
    static String duration(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }

        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m";
        }

        long hours = minutes / 60;
        if (hours < 24) {
            return String.format(Locale.ROOT, "%dh %02dm", hours, minutes % 60);
        }

        return (hours / 24) + "d " + (hours % 24) + "h";
    }
}
