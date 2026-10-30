package org.tsc.dispatch.util;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Small structured logger: one line per event, {@code key=value} pairs after the message.
 * Writes to stdout and optionally mirrors to a file. Deliberately dependency free so the
 * service can run from a bare jar on a headless server.
 */
public final class Log {

	public enum Level {
		TRACE, DEBUG, INFO, WARN, ERROR, OFF
	}

	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
	private static final PrintStream CONSOLE = System.out;
	private static volatile Level level = Level.INFO;
	private static volatile Path file;
	private static volatile boolean fileBroken;

	private Log() {
	}

	public static void configure(Level newLevel, Path logFile) {
		level = newLevel == null ? Level.INFO : newLevel;
		file = logFile;
		fileBroken = false;
		if (logFile != null) {
			try {
				if (logFile.getParent() != null) {
					Files.createDirectories(logFile.getParent());
				}
				Files.writeString(logFile, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			} catch (IOException exception) {
				fileBroken = true;
				CONSOLE.println(timestamp() + " WARN  log file unavailable path=" + logFile + " error=" + exception.getMessage());
			}
		}
	}

	public static boolean enabled(Level candidate) {
		return candidate.ordinal() >= level.ordinal() && level != Level.OFF;
	}

	public static void trace(String message, Object... pairs) {
		write(Level.TRACE, message, null, pairs);
	}

	public static void debug(String message, Object... pairs) {
		write(Level.DEBUG, message, null, pairs);
	}

	public static void info(String message, Object... pairs) {
		write(Level.INFO, message, null, pairs);
	}

	public static void warn(String message, Object... pairs) {
		write(Level.WARN, message, null, pairs);
	}

	public static void error(String message, Object... pairs) {
		write(Level.ERROR, message, null, pairs);
	}

	public static void error(String message, Throwable throwable, Object... pairs) {
		write(Level.ERROR, message, throwable, pairs);
	}

	private static synchronized void write(Level eventLevel, String message, Throwable throwable, Object... pairs) {
		if (!enabled(eventLevel)) {
			return;
		}
		final StringBuilder line = new StringBuilder(128);
		line.append(timestamp()).append(' ').append(pad(eventLevel.name())).append(' ').append(message);
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			line.append(' ').append(pairs[i]).append('=').append(stringify(pairs[i + 1]));
		}
		if (throwable != null) {
			line.append(" error=").append(throwable.getClass().getSimpleName()).append(':').append(String.valueOf(throwable.getMessage()).replace(' ', '_'));
		}
		final String text = line.toString();
		CONSOLE.println(text);
		if (throwable != null && enabled(Level.DEBUG)) {
			throwable.printStackTrace(CONSOLE);
		}
		final Path target = file;
		if (target != null && !fileBroken) {
			try {
				Files.writeString(target, text + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (IOException exception) {
				fileBroken = true;
				CONSOLE.println(timestamp() + " WARN  log file write failed path=" + target + " error=" + exception.getMessage());
			}
		}
	}

	private static String timestamp() {
		return TIMESTAMP.format(Instant.now());
	}

	private static String pad(String name) {
		return name.length() >= 5 ? name : name + " ".repeat(5 - name.length());
	}

	private static String stringify(Object value) {
		if (value == null) {
			return "null";
		}
		final String text = String.valueOf(value);
		return text.indexOf(' ') >= 0 || text.isEmpty() ? '"' + text + '"' : text;
	}
}
