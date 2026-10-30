package org.tsc.dispatch.persist;

import org.tsc.dispatch.util.Json;
import org.tsc.dispatch.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Crash-safe state directory.
 *
 * <p>{@code dispatch-state.json} holds the sequence counter, the idempotency window and the
 * lease table; it is rewritten atomically (temp file + move) so a crash never leaves a
 * truncated document behind. {@code commands.jsonl} is an append-only audit trail that
 * survives restarts and is safe to tail with any log tool.</p>
 */
public final class StateStore {

	private final Path directory;
	private final Path stateFile;
	private final Path journalFile;

	public StateStore(Path directory) {
		this.directory = directory;
		this.stateFile = directory.resolve("dispatch-state.json");
		this.journalFile = directory.resolve("commands.jsonl");
	}

	public Path directory() {
		return directory;
	}

	public Map<String, Object> load() {
		try {
			Files.createDirectories(directory);
			if (!Files.exists(stateFile)) {
				return new LinkedHashMap<>();
			}
			final Object parsed = Json.parse(Files.readString(stateFile, StandardCharsets.UTF_8));
			return Json.asObject(parsed);
		} catch (Exception exception) {
			Log.warn("state file unreadable, starting clean", "file", stateFile, "error", exception.getMessage());
			return new LinkedHashMap<>();
		}
	}

	public void save(Map<String, Object> state) {
		try {
			Files.createDirectories(directory);
			final Path temporary = directory.resolve("dispatch-state.json.tmp");
			Files.writeString(temporary, Json.writePretty(state), StandardCharsets.UTF_8);
			try {
				Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException exception) {
				Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception exception) {
			Log.error("failed to persist dispatch state", "error", exception.getMessage());
		}
	}

	public void journal(Map<String, Object> entry) {
		try {
			Files.createDirectories(directory);
			Files.writeString(journalFile, Json.write(entry) + System.lineSeparator(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException exception) {
			Log.warn("journal append failed", "error", exception.getMessage());
		}
	}
}
