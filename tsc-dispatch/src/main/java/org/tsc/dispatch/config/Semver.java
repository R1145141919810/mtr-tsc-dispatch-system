package org.tsc.dispatch.config;

/**
 * Minimal dotted version comparison. Build metadata and pre-release suffixes are ignored on
 * purpose: an {@code -beta} build of the same API level is treated as that level, because the
 * decision that matters is whether the HTTP contract matches, not marketing.
 */
public final class Semver {

	private Semver() {
	}

	public static int[] parse(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		final String cleaned = value.trim().replaceFirst("^[vV]", "").split("\\+")[0].split("-")[0];
		final String[] parts = cleaned.split("\\.");
		final int[] numbers = new int[]{0, 0, 0};
		for (int index = 0; index < Math.min(3, parts.length); index++) {
			try {
				numbers[index] = Integer.parseInt(parts[index].replaceAll("[^0-9]", ""));
			} catch (NumberFormatException exception) {
				return null;
			}
		}
		return numbers;
	}

	public static int compare(String left, String right) {
		final int[] a = parse(left);
		final int[] b = parse(right);
		if (a == null || b == null) {
			return 0;
		}
		for (int index = 0; index < 3; index++) {
			if (a[index] != b[index]) {
				return Integer.compare(a[index], b[index]);
			}
		}
		return 0;
	}

	public static boolean inRange(String version, String min, String max) {
		if (version == null || version.isBlank()) {
			return false;
		}
		if (min != null && !min.isBlank() && compare(version, min) < 0) {
			return false;
		}
		return max == null || max.isBlank() || compare(version, max) <= 0;
	}
}
