package org.tsc.dispatch.dispatch;

import org.tsc.dispatch.actuator.Actuator;
import org.tsc.dispatch.config.Config;
import org.tsc.dispatch.model.Vehicle;
import org.tsc.dispatch.model.WorldSnapshot;
import org.tsc.dispatch.persist.StateStore;
import org.tsc.dispatch.util.Json;
import org.tsc.dispatch.util.Log;
import org.tsc.dispatch.util.Metrics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Delivery layer: assigns sequence numbers, enforces idempotency and preconditions, retries
 * transient failures with exponential backoff and journals every attempt.
 *
 * <p>Ordering matters more than speed here. A command is only sent after its precondition has
 * been re-checked against the freshest snapshot, which is what keeps a decision computed from
 * 2 s old telemetry from landing on a train that already moved.</p>
 */
public final class CommandBus {

	private final Config config;
	private final StateStore store;
	private final Metrics metrics;
	private final LeaseTable leases;
	private final Actuator actuator;

	private final Map<String, Long> applied = new LinkedHashMap<>();
	private long sequence;

	public CommandBus(Config config, StateStore store, Metrics metrics, LeaseTable leases, Actuator actuator) {
		this.config = config;
		this.store = store;
		this.metrics = metrics;
		this.leases = leases;
		this.actuator = actuator;
	}

	public void init() {
		final Map<String, Object> state = store.load();
		sequence = Json.number(state, "sequence", 0);
		final long now = System.currentTimeMillis();
		final long window = config.getLong("dispatch.dedup-window-seconds") * 1000L;
		for (final Object element : Json.asArray(state.get("applied"))) {
			final Map<String, Object> item = Json.asObject(element);
			final String key = Json.string(item, "key", "");
			final long at = Json.number(item, "at", 0);
			if (!key.isEmpty() && now - at < window) {
				applied.put(key, at);
			}
		}
		leases.load(Json.asObject(state.get("leases")), now);
		Log.info("dispatch state restored", "sequence", sequence, "idempotencyKeys", applied.size(), "leases", leases.snapshot().size());
	}

	public synchronized long nextSequence() {
		return ++sequence;
	}

	public synchronized void persist() {
		final Map<String, Object> state = new LinkedHashMap<>();
		state.put("version", 1);
		state.put("savedAt", System.currentTimeMillis());
		state.put("sequence", sequence);
		final java.util.List<Object> keys = new java.util.ArrayList<>();
		applied.forEach((key, at) -> {
			final Map<String, Object> item = new LinkedHashMap<>();
			item.put("key", key);
			item.put("at", at);
			keys.add(item);
		});
		state.put("applied", keys);
		state.put("leases", leases.toJson().get("leases"));
		store.save(state);
	}

	public void submit(Decision decision, WorldSnapshot snapshot) {
		final Command command = decision.command;
		if (command == null) {
			return;
		}
		if (!actuator.supports().contains(command.kind)) {
			decision.status = Decision.Status.ADVISORY;
			decision.outcome = "actuator '" + actuator.kind() + "' cannot carry " + command.kind.wire;
			metrics.inc("dispatch_advisories_total{reason=unsupported}");
			return;
		}

		final long now = System.currentTimeMillis();
		if (command.expiresAtMillis > 0 && now > command.expiresAtMillis) {
			decision.status = Decision.Status.SKIPPED;
			decision.outcome = "expired before delivery";
			metrics.inc("dispatch_skipped_total{reason=expired}");
			return;
		}

		final long window = config.getLong("dispatch.dedup-window-seconds") * 1000L;
		final Long previous = applied.get(command.idempotencyKey);
		if (previous != null && now - previous < window) {
			decision.status = Decision.Status.SKIPPED;
			decision.outcome = "duplicate of " + command.idempotencyKey;
			metrics.inc("dispatch_skipped_total{reason=duplicate}");
			return;
		}

		final Vehicle observed = snapshot.vehicle(command.preconditionVehicleId);
		if (observed == null) {
			decision.status = Decision.Status.SKIPPED;
			decision.outcome = "vehicle no longer observed";
			metrics.inc("dispatch_skipped_total{reason=vanished}");
			return;
		}
		if (!command.preconditionPlatformHex.isEmpty() && !command.preconditionPlatformHex.equals(observed.platformHex)) {
			decision.status = Decision.Status.SKIPPED;
			decision.outcome = "precondition changed (now " + observed.platformHex + ")";
			metrics.inc("dispatch_skipped_total{reason=stale}");
			Log.debug("stale command dropped", "vehicle", command.vehicleId, "expected", command.preconditionPlatformHex, "actual", observed.platformHex);
			return;
		}

		command.seq = nextSequence();
		command.issuedAtMillis = now;
		final int maxAttempts = Math.max(1, command.maxAttempts);
		Actuator.Result result = Actuator.Result.retryable("not attempted");

		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			result = actuator.execute(command);
			metrics.inc("dispatch_attempts_total{kind=" + command.kind.wire + "}");
			if (result.success()) {
				break;
			}
			if (!result.retryable() || attempt == maxAttempts) {
				break;
			}
			final long backoff = config.getLong("dispatch.retry-backoff-ms") * (1L << (attempt - 1));
			try {
				Thread.sleep(backoff);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				break;
			}
		}

		if (result.success()) {
			applied.put(command.idempotencyKey, now);
			decision.status = result.applied() ? Decision.Status.APPLIED : Decision.Status.ACCEPTED;
			decision.outcome = result.detail();
			metrics.inc("dispatch_commands_total{result=" + (result.applied() ? "applied" : "accepted") + "}");
		} else {
			leases.releaseAll(command.vehicleId);
			decision.status = Decision.Status.FAILED;
			decision.outcome = result.detail();
			metrics.inc("dispatch_commands_total{result=failed}");
		}

		final Map<String, Object> journal = new LinkedHashMap<>(command.toJson());
		journal.put("status", decision.status.name());
		journal.put("outcome", decision.outcome);
		journal.put("type", decision.type);
		store.journal(journal);
		persist();
	}

	public Actuator actuator() {
		return actuator;
	}

	public synchronized Map<String, Object> describe() {
		final Map<String, Object> result = new LinkedHashMap<>();
		result.put("sequence", sequence);
		result.put("idempotencyKeys", applied.size());
		result.put("actuator", actuator.kind());
		final java.util.List<Object> kinds = new java.util.ArrayList<>();
		actuator.supports().forEach(kind -> kinds.add(kind.wire));
		result.put("supportedCommands", kinds);
		return result;
	}
}
