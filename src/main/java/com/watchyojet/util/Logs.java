package com.watchyojet.util;

import java.io.PrintStream;
import java.text.MessageFormat;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ResourceBundle;

/**
 * Application loggers behind the JDK's {@link System.Logger} interface. Each record prints on
 * one line as {@code HH:mm:ss LEVEL message} to standard output.
 *
 * <p>This deliberately does not go through java.util.logging: JavaFX and WebKit configure that
 * framework during startup, which would override the format. The threshold defaults to INFO
 * and can be changed with {@code -Dwatchyojet.log.level=DEBUG}.
 */
public final class Logs {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final System.Logger.Level THRESHOLD = threshold();

    private Logs() {
    }

    public static System.Logger get(Class<?> owner) {
        return new LineLogger(owner.getName(), System.out);
    }

    private static System.Logger.Level threshold() {
        String configured = System.getProperty("watchyojet.log.level", "INFO");
        try {
            return System.Logger.Level.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return System.Logger.Level.INFO;
        }
    }

    static final class LineLogger implements System.Logger {
        private final String name;
        private final PrintStream out;

        LineLogger(String name, PrintStream out) {
            this.name = name;
            this.out = out;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isLoggable(Level level) {
            return level != Level.OFF && level.getSeverity() >= THRESHOLD.getSeverity();
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
            if (!isLoggable(level)) return;
            synchronized (out) {
                out.printf("%s %-7s %s%n", LocalTime.now().format(TIME), level.getName(), msg);
                if (thrown != null) thrown.printStackTrace(out);
            }
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            if (!isLoggable(level)) return;
            String msg = params == null || params.length == 0 ? format : MessageFormat.format(format, params);
            log(level, bundle, msg, (Throwable) null);
        }
    }
}
