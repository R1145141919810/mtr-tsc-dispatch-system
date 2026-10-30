package org.tsc.dispatch.util;

/**
 * TSC identifies every persisted entity by a {@code long} and exposes it on the wire as a
 * zero padded 16 character hexadecimal string (see {@code NameColorDataBase.getHexId()}).
 * These helpers are the single place that conversion lives.
 */
public final class Ids {

	private Ids() {
	}

	public static String hex(long value) {
		return String.format("%016x", value);
	}

	public static long parse(String hex, long fallback) {
		if (hex == null || hex.isBlank()) {
			return fallback;
		}
		final String trimmed = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
		try {
			return Long.parseUnsignedLong(trimmed, 16);
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	public static String normalize(String hex) {
		return hex(parse(hex, 0L));
	}

	/** Key used for station/platform nodes so that hex and numeric ids collapse together. */
	public static String node(String hex) {
		return normalize(hex);
	}
}
