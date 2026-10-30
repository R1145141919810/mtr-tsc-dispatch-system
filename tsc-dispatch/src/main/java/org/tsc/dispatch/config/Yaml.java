package org.tsc.dispatch.config;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal YAML reader for the dispatch configuration file.
 *
 * <p>Supports the subset the schema actually uses: block mappings, block sequences,
 * flow sequences ({@code [a, b]}), quoted and plain scalars, comments and blank lines.
 * Deliberately not a general YAML implementation - no anchors, tags, multi-document
 * streams or block scalars - because a configuration file that needs those is a
 * configuration file that is too clever to review.</p>
 */
public final class Yaml {

	private Yaml() {
	}

	public static final class YamlException extends RuntimeException {
		public YamlException(String message) {
			super(message);
		}
	}

	private record Line(int indent, String text, int number) {
	}

	public static Map<String, Object> parse(String text) {
		final List<Line> lines = tokenize(text);
		if (lines.isEmpty()) {
			return new LinkedHashMap<>();
		}
		final Cursor cursor = new Cursor(lines);
		final Object root = cursor.parseBlock(lines.get(0).indent());
		if (!(root instanceof Map<?, ?>)) {
			throw new YamlException("top level must be a mapping");
		}
		if (cursor.hasNext()) {
			throw new YamlException("unexpected content at line " + cursor.peek().number());
		}
		@SuppressWarnings("unchecked")
		final Map<String, Object> map = (Map<String, Object>) root;
		return map;
	}

	// ---------------------------------------------------------------- tokenizer

	private static List<Line> tokenize(String text) {
		final List<Line> result = new ArrayList<>();
		final String[] raw = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
		for (int index = 0; index < raw.length; index++) {
			String line = raw[index];
			if (index == 0 && line.startsWith("\uFEFF")) {
				line = line.substring(1);
			}
			final String withoutComment = stripComment(line);
			if (withoutComment.isBlank()) {
				continue;
			}
			int indent = 0;
			while (indent < withoutComment.length() && withoutComment.charAt(indent) == ' ') {
				indent++;
			}
			if (indent < withoutComment.length() && withoutComment.charAt(indent) == '\t') {
				throw new YamlException("tabs are not allowed for indentation (line " + (index + 1) + ")");
			}
			result.add(new Line(indent, withoutComment.substring(indent).stripTrailing(), index + 1));
		}
		return result;
	}

	private static String stripComment(String line) {
		boolean inSingle = false;
		boolean inDouble = false;
		for (int index = 0; index < line.length(); index++) {
			final char c = line.charAt(index);
			if (c == '\'' && !inDouble) {
				inSingle = !inSingle;
			} else if (c == '"' && !inSingle) {
				inDouble = !inDouble;
			} else if (c == '#' && !inSingle && !inDouble && (index == 0 || Character.isWhitespace(line.charAt(index - 1)))) {
				return line.substring(0, index);
			}
		}
		return line;
	}

	// ---------------------------------------------------------------- parser

	private static final class Cursor {
		private final List<Line> lines;
		private int position;

		private Cursor(List<Line> lines) {
			this.lines = lines;
		}

		private boolean hasNext() {
			return position < lines.size();
		}

		private Line peek() {
			return lines.get(position);
		}

		private Object parseBlock(int indent) {
			final Line line = peek();
			if (line.text().startsWith("- ") || "-".equals(line.text())) {
				return parseSequence(indent);
			}
			return parseMapping(indent);
		}

		private Map<String, Object> parseMapping(int indent) {
			final Map<String, Object> result = new LinkedHashMap<>();
			while (hasNext() && peek().indent() == indent) {
				final Line line = peek();
				if (line.text().startsWith("-")) {
					break;
				}
				final Map.Entry<String, String> entry = splitEntry(line.text(), line.number());
				position++;
				if (entry.getValue().isEmpty()) {
					if (hasNext() && peek().indent() > indent) {
						result.put(entry.getKey(), parseBlock(peek().indent()));
					} else {
						result.put(entry.getKey(), "");
					}
				} else {
					result.put(entry.getKey(), scalar(entry.getValue()));
				}
			}
			return result;
		}

		private List<Object> parseSequence(int indent) {
			final List<Object> result = new ArrayList<>();
			while (hasNext() && peek().indent() == indent) {
				final Line line = peek();
				if (!line.text().startsWith("-")) {
					break;
				}
				final String item = line.text().substring(1).trim();
				position++;
				if (item.isEmpty()) {
					result.add(hasNext() && peek().indent() > indent ? parseBlock(peek().indent()) : "");
				} else if (looksLikeEntry(item)) {
					result.add(parseInlineItemMap(item, indent, line.number()));
				} else {
					result.add(scalar(item));
				}
			}
			return result;
		}

		/** {@code - key: value} plus any following keys indented under the dash. */
		private Map<String, Object> parseInlineItemMap(String first, int indent, int number) {
			final Map<String, Object> map = new LinkedHashMap<>();
			final Map.Entry<String, String> entry = splitEntry(first, number);
			if (entry.getValue().isEmpty()) {
				if (hasNext() && peek().indent() > indent + 1) {
					map.put(entry.getKey(), parseBlock(peek().indent()));
				} else {
					map.put(entry.getKey(), "");
				}
			} else {
				map.put(entry.getKey(), scalar(entry.getValue()));
			}
			if (hasNext() && peek().indent() > indent && !peek().text().startsWith("-")) {
				final Map<String, Object> more = parseMapping(peek().indent());
				more.forEach(map::putIfAbsent);
			}
			return map;
		}

		private static boolean looksLikeEntry(String text) {
			final int colon = findSeparator(text);
			return colon > 0;
		}

		private static Map.Entry<String, String> splitEntry(String text, int number) {
			final int colon = findSeparator(text);
			if (colon <= 0) {
				throw new YamlException("expected 'key: value' at line " + number);
			}
			final String key = unquote(text.substring(0, colon).trim());
			final String value = text.substring(colon + 1).trim();
			return new AbstractMap.SimpleEntry<>(key, value);
		}

		/** Finds the {@code key:} separator, ignoring colons inside flow collections. */
		private static int findSeparator(String text) {
			int depth = 0;
			boolean inSingle = false;
			boolean inDouble = false;
			for (int index = 0; index < text.length(); index++) {
				final char c = text.charAt(index);
				if (c == '\'' && !inDouble) {
					inSingle = !inSingle;
				} else if (c == '"' && !inSingle) {
					inDouble = !inDouble;
				} else if (!inSingle && !inDouble) {
					if (c == '[' || c == '{') {
						depth++;
					} else if (c == ']' || c == '}') {
						depth--;
					} else if (c == ':' && depth == 0) {
						return index;
					}
				}
			}
			return -1;
		}
	}

	// ---------------------------------------------------------------- scalars

	static Object scalar(String raw) {
		final String value = raw.trim();
		if (value.isEmpty()) {
			return "";
		}
		if (value.startsWith("[") && value.endsWith("]")) {
			return flowSequence(value);
		}
		if (value.startsWith("{") && value.endsWith("}")) {
			return flowMapping(value);
		}
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			return unescape(value.substring(1, value.length() - 1));
		}
		if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
			return value.substring(1, value.length() - 1).replace("''", "'");
		}
		if ("null".equalsIgnoreCase(value) || "~".equals(value)) {
			return null;
		}
		if ("true".equalsIgnoreCase(value)) {
			return Boolean.TRUE;
		}
		if ("false".equalsIgnoreCase(value)) {
			return Boolean.FALSE;
		}
		if (value.matches("[-+]?\\d+")) {
			try {
				return Long.parseLong(value);
			} catch (NumberFormatException ignored) {
				return value;
			}
		}
		if (value.matches("[-+]?\\d*\\.\\d+([eE][-+]?\\d+)?") || value.matches("[-+]?\\d+[eE][-+]?\\d+")) {
			try {
				return Double.parseDouble(value);
			} catch (NumberFormatException ignored) {
				return value;
			}
		}
		return value;
	}

	private static List<Object> flowSequence(String value) {
		final List<Object> result = new ArrayList<>();
		for (final String part : splitFlow(value.substring(1, value.length() - 1))) {
			if (!part.isBlank()) {
				result.add(scalar(part));
			}
		}
		return result;
	}

	private static Map<String, Object> flowMapping(String value) {
		final Map<String, Object> result = new LinkedHashMap<>();
		for (final String part : splitFlow(value.substring(1, value.length() - 1))) {
			if (part.isBlank()) {
				continue;
			}
			final int colon = part.indexOf(':');
			if (colon > 0) {
				result.put(unquote(part.substring(0, colon).trim()), scalar(part.substring(colon + 1)));
			}
		}
		return result;
	}

	private static List<String> splitFlow(String body) {
		final List<String> parts = new ArrayList<>();
		final StringBuilder current = new StringBuilder();
		boolean inSingle = false;
		boolean inDouble = false;
		for (int index = 0; index < body.length(); index++) {
			final char c = body.charAt(index);
			if (c == '\'' && !inDouble) {
				inSingle = !inSingle;
			} else if (c == '"' && !inSingle) {
				inDouble = !inDouble;
			}
			if (c == ',' && !inSingle && !inDouble) {
				parts.add(current.toString().trim());
				current.setLength(0);
			} else {
				current.append(c);
			}
		}
		if (!current.isEmpty()) {
			parts.add(current.toString().trim());
		}
		return parts;
	}

	private static String unquote(String value) {
		if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
			return value.substring(1, value.length() - 1);
		}
		return value;
	}

	private static String unescape(String value) {
		final StringBuilder builder = new StringBuilder(value.length());
		for (int index = 0; index < value.length(); index++) {
			final char c = value.charAt(index);
			if (c == '\\' && index + 1 < value.length()) {
				final char next = value.charAt(++index);
				switch (next) {
					case 'n' -> builder.append('\n');
					case 't' -> builder.append('\t');
					case 'r' -> builder.append('\r');
					default -> builder.append(next);
				}
			} else {
				builder.append(c);
			}
		}
		return builder.toString();
	}
}
