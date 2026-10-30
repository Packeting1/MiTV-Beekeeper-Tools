package io.github.rin.beekeeper.rgb;

import java.util.Locale;

public final class RgbColor {
    public static int parse(String input) {
        String value = input == null ? "" : input.trim();
        if (value.startsWith("#")) value = value.substring(1);
        if (!value.matches("[0-9a-fA-F]{6}"))
            throw new IllegalArgumentException("请输入六位颜色值，例如 #66FFCC");
        return Integer.parseInt(value, 16);
    }

    public static String format(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xffffff);
    }

    public static int encode(int rgb) { return 0x01000000 | (rgb & 0xffffff); }

    private RgbColor() { }
}
