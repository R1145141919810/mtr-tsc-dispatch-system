package org.tsc.dispatch.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON codec.
 *
 * <p>The whole service is shipped as a single jar with no third-party runtime
 * dependencies, so it carries its own parser/writer. The supported grammar is
 * strict RFC 8259 JSON: objects, arrays, strings, numbers, booleans and null.
 * Numbers are returned as {@link Long} when they have no fraction/exponent and
 * fit, otherwise as {@link Double}.</p>
 */
public final class Json {

	private Json() {
	}

	public static final class ParseException extends RuntimeException {
		public ParseException(String message) {
			super(message);
		}
	}

	// ---------------------------------------------------------------- parsing

	public static Object parse(String text) {
		final Parser parser = new Parser(text);
		parser.skipWhitespace();
		final Object value = parser.readValue();
		parser.skipWhitespace();
		if (!parser.atEnd()) {
			throw new ParseException("trailing content at offset " + parser.position);
		}
		return value;
	}

	private static final class Parser {
		private final String text;
		private int position;

		private Parser(String text) {
			this.text = text;
		}

		private boolean atEnd() {
			return position >= text.length();
		}

		private void skipWhitespace() {
			while (position < text.length()) {
				final char c = text.charAt(position);
				if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
					position++;
				} else {
					break;
				}
			}
		}

		private char peek() {
			if (atEnd()) {
				throw new ParseException("unexpected end of input");
			}
			return text.charAt(position);
		}

		private void expect(char expected) {
			if (atEnd() || text.charAt(position) != expected) {
				throw new ParseException("expected '" + expected + "' at offset " + position);
			}
			position++;
		}

		private Object readValue() {
			skipWhitespace();
			final char c = peek();
			switch (c) {
				case '{':
					return readObject();
				case '[':
					return readArray();
				case '"':
					return readString();
				case 't':
					readLiteral("true");
					return Boolean.TRUE;
				case 'f':
					readLiteral("false");
					return Boolean.FALSE;
				case 'n':
					readLiteral("null");
					return null;
				default:
					return readNumber();
			}
		}

		private void readLiteral(String literal) {
			if (!text.startsWith(literal, position)) {
				throw new ParseException("invalid literal at offset " + position);
			}
			position += literal.length();
		}

		private Map<String, Object> readObject() {
			expect('{');
			final Map<String, Object> result = new LinkedHashMap<>();
			skipWhitespace();
			if (!atEnd() && peek() == '}') {
				position++;
				return result;
			}
			while (true) {
				skipWhitespace();
				final String key = readString();
				skipWhitespace();
				expect(':');
				result.put(key, readValue());
				skipWhitespace();
				final char next = peek();
				if (next == ',') {
					position++;
				} else if (next == '}') {
					position++;
					return result;
				} else {
					throw new ParseException("expected ',' or '}' at offset " + position);
				}
			}
		}

		private List<Object> readArray() {
			expect('[');
			final List<Object> result = new ArrayList<>();
			skipWhitespace();
			if (!atEnd() && peek() == ']') {
				position++;
				return result;
			}
			while (true) {
				result.add(readValue());
				skipWhitespace();
				final char next = peek();
				if (next == ',') {
					position++;
				} else if (next == ']') {
					position++;
					return result;
				} else {
					throw new ParseException("expected ',' or ']' at offset " + position);
				}
			}
		}

		private String readString() {
			expect('"');
			final StringBuilder builder = new StringBuilder();
			while (true) {
				if (atEnd()) {
					throw new ParseException("unterminated string");
				}
				final char c = text.charAt(position++);
				if (c == '"') {
					return builder.toString();
				}
				if (c != '\\') {
					builder.append(c);
					continue;
				}
				if (atEnd()) {
					throw new ParseException("unterminated escape");
				}
				final char escaped = text.charAt(position++);
				switch (escaped) {
					case '"' -> builder.append('"');
					case '\\' -> builder.append('\\');
					case '/' -> builder.append('/');
					case 'b' -> builder.append('\b');
					case 'f' -> builder.append('\f');
					case 'n' -> builder.append('\n');
					case 'r' -> builder.append('\r');
					case 't' -> builder.append('\t');
					case 'u' -> {
						if (position + 4 > text.length()) {
							throw new ParseException("truncated unicode escape");
						}
						builder.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
						position += 4;
					}
					default -> throw new ParseException("invalid escape '\\" + escaped + "'");
				}
			}
		}

		private Object readNumber() {
			final int start = position;
			if (!atEnd() && (peek() == '-' || peek() == '+')) {
				position++;
			}
			boolean fraction = false;
			while (!atEnd()) {
				final char c = text.charAt(position);
				if (c >= '0' && c <= '9') {
					position++;
				} else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
					fraction = true;
					position++;
				} else {
					break;
				}
			}
			if (start == position) {
				throw new ParseException("invalid value at offset " + start);
			}
			final String raw = text.substring(start, position);
			try {
				return fraction ? (Object) Double.parseDouble(raw) : (Object) Long.parseLong(raw);
			} catch (NumberFormatException exception) {
				return Double.parseDouble(raw);
			}
		}
	}

	// ---------------------------------------------------------------- writing

	public static String write(Object value) {
		final StringBuilder builder = new StringBuilder();
		writeValue(builder, value);
		return builder.toString();
	}

	public static String writePretty(Object value) {
		final StringBuilder builder = new StringBuilder();
		writeValuePretty(builder, value, 0);
		return builder.toString();
	}

	private static void writeValue(StringBuilder builder, Object value) {
		if (value == null) {
			builder.append("null");
		} else if (value instanceof String string) {
			writeString(builder, string);
		} else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
			builder.append(value);
		} else if (value instanceof Number number) {
			writeNumber(builder, number.doubleValue());
		} else if (value instanceof Map<?, ?> map) {
			builder.append('{');
			boolean first = true;
			for (final Map.Entry<?, ?> entry : map.entrySet()) {
				if (!first) {
					builder.append(',');
				}
				first = false;
				writeString(builder, String.valueOf(entry.getKey()));
				builder.append(':');
				writeValue(builder, entry.getValue());
			}
			builder.append('}');
		} else if (value instanceof Iterable<?> iterable) {
			builder.append('[');
			boolean first = true;
			for (final Object element : iterable) {
				if (!first) {
					builder.append(',');
				}
				first = false;
				writeValue(builder, element);
			}
			builder.append(']');
		} else if (value instanceof Object[] array) {
			writeValue(builder, List.of(array));
		} else if (value instanceof double[] array) {
			builder.append('[');
			for (int i = 0; i < array.length; i++) {
				if (i > 0) {
					builder.append(',');
				}
				writeNumber(builder, array[i]);
			}
			builder.append(']');
		} else {
			writeString(builder, String.valueOf(value));
		}
	}

	private static void writeValuePretty(StringBuilder builder, Object value, int indent) {
		final String pad = "  ".repeat(indent);
		if (value instanceof Map<?, ?> map && !map.isEmpty()) {
			builder.append("{\n");
			boolean first = true;
			for (final Map.Entry<?, ?> entry : map.entrySet()) {
				if (!first) {
					builder.append(",\n");
				}
				first = false;
				builder.append(pad).append("  ");
				writeString(builder, String.valueOf(entry.getKey()));
				builder.append(": ");
				writeValuePretty(builder, entry.getValue(), indent + 1);
			}
			builder.append('\n').append(pad).append('}');
		} else if (value instanceof Iterable<?> iterable && iterable.iterator().hasNext()) {
			builder.append("[\n");
			boolean first = true;
			for (final Object element : iterable) {
				if (!first) {
					builder.append(",\n");
				}
				first = false;
				builder.append(pad).append("  ");
				writeValuePretty(builder, element, indent + 1);
			}
			builder.append('\n').append(pad).append(']');
		} else {
			writeValue(builder, value);
		}
	}

	private static void writeNumber(StringBuilder builder, double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			builder.append(0);
		} else if (value == Math.rint(value) && Math.abs(value) < 9.0E15) {
			builder.append((long) value);
		} else {
			builder.append(value);
		}
	}

	private static void writeString(StringBuilder builder, String value) {
		builder.append('"');
		for (int i = 0; i < value.length(); i++) {
			final char c = value.charAt(i);
			switch (c) {
				case '"' -> builder.append("\\\"");
				case '\\' -> builder.append("\\\\");
				case '\b' -> builder.append("\\b");
				case '\f' -> builder.append("\\f");
				case '\n' -> builder.append("\\n");
				case '\r' -> builder.append("\\r");
				case '\t' -> builder.append("\\t");
				default -> {
					if (c < 0x20) {
						builder.append(String.format("\\u%04x", (int) c));
					} else {
						builder.append(c);
					}
				}
			}
		}
		builder.append('"');
	}

	// ---------------------------------------------------------------- helpers

	@SuppressWarnings("unchecked")
	public static Map<String, Object> asObject(Object value) {
		return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
	}

	@SuppressWarnings("unchecked")
	public static List<Object> asArray(Object value) {
		return value instanceof List ? (List<Object>) value : new ArrayList<>();
	}

	public static String string(Map<String, Object> object, String key, String fallback) {
		final Object value = object == null ? null : object.get(key);
		return value == null ? fallback : String.valueOf(value);
	}

	public static long number(Map<String, Object> object, String key, long fallback) {
		final Object value = object == null ? null : object.get(key);
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value instanceof String string) {
			try {
				return Long.parseLong(string.contains(".") ? String.valueOf((long) Double.parseDouble(string)) : string);
			} catch (NumberFormatException ignored) {
				return fallback;
			}
		}
		return fallback;
	}

	public static double decimal(Map<String, Object> object, String key, double fallback) {
		final Object value = object == null ? null : object.get(key);
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		if (value instanceof String string) {
			try {
				return Double.parseDouble(string);
			} catch (NumberFormatException ignored) {
				return fallback;
			}
		}
		return fallback;
	}

	public static boolean bool(Map<String, Object> object, String key, boolean fallback) {
		final Object value = object == null ? null : object.get(key);
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value instanceof String string) {
			return Boolean.parseBoolean(string);
		}
		return fallback;
	}

	/** Dot-path lookup: {@code "data.routes.0.stations"} with array indices as segments. */
	public static Object at(Object root, String path) {
		Object current = root;
		for (final String segment : path.split("\\.")) {
			if (current instanceof Map<?, ?> map) {
				current = map.get(segment);
			} else if (current instanceof List<?> list) {
				try {
					final int index = Integer.parseInt(segment);
					current = index >= 0 && index < list.size() ? list.get(index) : null;
				} catch (NumberFormatException ignored) {
					return null;
				}
			} else {
				return null;
			}
			if (current == null) {
				return null;
			}
		}
		return current;
	}
}
