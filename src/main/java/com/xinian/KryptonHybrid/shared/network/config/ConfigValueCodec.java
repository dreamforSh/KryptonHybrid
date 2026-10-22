package com.xinian.KryptonHybrid.shared.network.config;

import java.util.Locale;

/**
 * Text form of config values for the server-config sync. Both sides share the same
 * {@code ForgeConfigSpec}, so the value type comes from the spec's default.
 */
public final class ConfigValueCodec {
    private ConfigValueCodec() {}

    public static String format(Object value) {
        return value instanceof Enum<?> e ? e.name() : String.valueOf(value);
    }

    /** Parses {@code text} into the type of {@code defaultValue}, or returns {@code null} if it does not fit. */
    public static Object parse(Object defaultValue, String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        try {
            if (defaultValue instanceof Boolean) {
                if (t.equalsIgnoreCase("true")) return Boolean.TRUE;
                if (t.equalsIgnoreCase("false")) return Boolean.FALSE;
                return null;
            }
            if (defaultValue instanceof Enum<?> e) {
                for (Object constant : e.getDeclaringClass().getEnumConstants()) {
                    if (((Enum<?>) constant).name().equalsIgnoreCase(t)) {
                        return constant;
                    }
                }
                return null;
            }
            if (defaultValue instanceof Integer) return Integer.parseInt(t);
            if (defaultValue instanceof Long) return Long.parseLong(t);
            if (defaultValue instanceof Double) return Double.parseDouble(t);
            if (defaultValue instanceof Float) return Float.parseFloat(t);
        } catch (NumberFormatException ex) {
            return null;
        }
        return defaultValue instanceof String ? text : null;
    }

    /** Secrets are write-only over the network: never sent to clients, never logged. */
    public static boolean isSecret(String path) {
        return path.toLowerCase(Locale.ROOT).contains("secret");
    }
}
