package io.timewheel.bench;

import java.util.HashMap;
import java.util.Map;

/** Command-line settings: {@code --key=value} pairs with defaults. */
public final class Settings {

    private final Map<String, String> values = new HashMap<>();

    public static Settings parse(String[] args) {
        Settings s = new Settings();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IllegalArgumentException("expected --key=value, got: " + arg);
            }
            int eq = arg.indexOf('=');
            s.values.put(arg.substring(2, eq), arg.substring(eq + 1));
        }
        return s;
    }

    public static Settings defaults() {
        return new Settings();
    }

    public String text(String key, String fallback) {
        return values.getOrDefault(key, fallback);
    }

    public int integer(String key, int fallback) {
        String v = values.get(key);
        return v == null ? fallback : Integer.parseInt(v.replace("_", ""));
    }

    public double decimal(String key, double fallback) {
        String v = values.get(key);
        return v == null ? fallback : Double.parseDouble(v);
    }
}
