// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

/**
 * Logging for the parts of Valtrin that also run without Minecraft (the engine and the server program).
 * Inside the game the mod points {@link #LOGGER} at its normal log; on the server it prints to standard error,
 * which systemd keeps in the journal.
 */
final class Log {
    private Log() {}

    /** The few logging calls Valtrin uses; same shape as the usual "{}" style loggers. */
    interface Out {
        void debug(String format, Object... args);
        void info(String format, Object... args);
        void warn(String format, Object... args);
        void error(String format, Object... args);
    }

    static volatile Out LOGGER = new Out() {
        public void debug(String f, Object... a) { /* quiet by default */ }
        public void info(String f, Object... a) { print("INFO ", f, a); }
        public void warn(String f, Object... a) { print("WARN ", f, a); }
        public void error(String f, Object... a) { print("ERROR", f, a); }
    };

    /** Replaces each "{}" with the next argument; a trailing exception is added as text. Never throws. */
    static String format(String format, Object... args) {
        try {
            StringBuilder sb = new StringBuilder();
            int used = 0, i = 0;
            String f = format == null ? "" : format;
            while (i < f.length()) {
                int at = f.indexOf("{}", i);
                if (at < 0 || args == null || used >= args.length) break;
                sb.append(f, i, at).append(String.valueOf(args[used++]));
                i = at + 2;
            }
            sb.append(f.substring(Math.min(i, f.length())));
            if (args != null && used < args.length && args[args.length - 1] instanceof Throwable t) {
                sb.append(" | ").append(t);
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return String.valueOf(format);
        }
    }

    private static void print(String level, String f, Object[] a) {
        System.err.println(java.time.Instant.now() + " " + level + " valtrin: " + format(f, a));
    }
}
