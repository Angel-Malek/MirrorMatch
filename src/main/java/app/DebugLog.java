package app;

/**
 * Lightweight opt-in logger. Disabled by default; when enabled it prints to stdout.
 */
public final class DebugLog {
    private static volatile boolean enabled = false;

    private DebugLog() {}

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void log(String msg) {
        if (!enabled) return;
        System.out.println("[DEBUG] " + msg);
    }

    public static void log(String fmt, Object... args) {
        if (!enabled) return;
        log(String.format(fmt, args));
    }
}
