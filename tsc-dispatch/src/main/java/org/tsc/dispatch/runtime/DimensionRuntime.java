package org.tsc.dispatch.runtime;

import org.tsc.dispatch.actuator.Actuator;
import org.tsc.dispatch.config.Config;
import org.tsc.dispatch.config.DimensionSpec;
import org.tsc.dispatch.dispatch.CommandBus;
import org.tsc.dispatch.dispatch.Dispatcher;
import org.tsc.dispatch.dispatch.LeaseTable;
import org.tsc.dispatch.model.Vehicle;
import org.tsc.dispatch.model.WorldSnapshot;
import org.tsc.dispatch.persist.StateStore;
import org.tsc.dispatch.source.BridgeSource;
import org.tsc.dispatch.source.CompatibilityProbe;
import org.tsc.dispatch.source.HttpJson;
import org.tsc.dispatch.source.StockTscSource;
import org.tsc.dispatch.source.TelemetrySource;
import org.tsc.dispatch.util.Log;
import org.tsc.dispatch.util.Metrics;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Everything one dimension needs, and nothing shared with its siblings.
 *
 * <p>State isolation is the point: two dimensions must never share a world model, a lease
 * table, a command sequence or a state directory, otherwise a train in the nether can steal a
 * platform lease from a train in the overworld. The only shared objects are the HTTP client
 * (connection pool) and the actuator (one process, one console).</p>
 */
public final class DimensionRuntime {

	private final DimensionSpec spec;
	private final Config config;
	private final Metrics metrics;
	private final HttpJson http;
	private final HttpJson bridgeHttp;
	private final Actuator actuator;
	private final java.util.function.BooleanSupplier preferBridge;
	private final String resolvedBaseUrl;

	private final LeaseTable leases = new LeaseTable();
	private final AtomicLong lastSuccessAt = new AtomicLong();
	private final AtomicLong failures = new AtomicLong();
	private final AtomicLong polls = new AtomicLong();

	private TelemetrySource stockSource;
	private TelemetrySource bridgeSource;
	private CommandBus bus;
	private Dispatcher dispatcher;
	private volatile WorldSnapshot snapshot;
	private volatile CompatibilityProbe.Result compatibility;
	private volatile String lastError = "";
	private volatile long effectiveIntervalMs;

	public DimensionRuntime(DimensionSpec spec, Config config, Metrics metrics, HttpJson http, HttpJson bridgeHttp,
							Actuator actuator, java.util.function.BooleanSupplier preferBridge, String resolvedBaseUrl) {
		this.spec = spec;
		this.config = config;
		this.metrics = metrics;
		this.http = http;
		this.bridgeHttp = bridgeHttp;
		this.actuator = actuator;
		this.preferBridge = preferBridge;
		this.resolvedBaseUrl = resolvedBaseUrl;
		this.effectiveIntervalMs = config.pollingInterval();
	}

	public TelemetrySource activeSource() {
		return preferBridge.getAsBoolean() && bridgeSource != null ? bridgeSource : stockSource;
	}

	public int index() {
		return spec.index();
	}

	public DimensionSpec spec() {
		return spec;
	}

	public void start() {
		stockSource = new StockTscSource(config, http, spec.index(), resolvedBaseUrl);
		// The bridge connection carries its own bearer token and timeout; reusing the stock TSC
		// client here would send every telemetry call without credentials (HTTP 401).
		bridgeSource = config.bridgeEnabled() && bridgeHttp != null ? new BridgeSource(config, bridgeHttp, spec.index()) : null;
		final Path stateDirectory = Path.of(config.get("state.dir"), spec.stateDirectoryName());
		bus = new CommandBus(config, new StateStore(stateDirectory), metrics, leases, actuator);
		bus.init();
		dispatcher = new Dispatcher(config, metrics, leases, bus);
		Log.info("dimension runtime ready", "dimension", spec.id(), "index", spec.index(),
			"telemetry", activeSource().name(), "state", stateDirectory, "intervalMs", effectiveIntervalMs);
	}

	public void tick() {
		final long startedAt = System.currentTimeMillis();
		try {
			final TelemetrySource source = activeSource();
			final WorldSnapshot fetched = source.fetch();
			final long latency = System.currentTimeMillis() - startedAt;
			polls.incrementAndGet();
			lastSuccessAt.set(System.currentTimeMillis());
			failures.set(0);
			snapshot = fetched;
			lastError = "";

			final CompatibilityProbe.Result previous = compatibility;
			if (previous == null || polls.get() % 15 == 0) {
				compatibility = CompatibilityProbe.evaluate(config, fetched);
				if (previous == null || !previous.level().equals(compatibility.level())) {
					Log.info("api compatibility", "dimension", spec.index(), "level", compatibility.level(),
						"missing", compatibility.missing());
				}
			}

			metrics.inc("telemetry_polls_total{result=ok,dimension=" + spec.index() + "}");
			metrics.gauge("telemetry_latency_ms{dimension=" + spec.index() + "}", latency);
			metrics.gauge("world_vehicles{dimension=" + spec.index() + "}", fetched.vehicles.size());

			final long decisionStart = System.currentTimeMillis();
			dispatcher.tick(fetched);
			metrics.gauge("dispatch_tick_ms{dimension=" + spec.index() + "}", System.currentTimeMillis() - decisionStart);
			adapt(latency, fetched.vehicles.size());
		} catch (Exception exception) {
			if (Thread.currentThread().isInterrupted()) {
				return;
			}
			final long count = failures.incrementAndGet();
			lastError = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
			metrics.inc("telemetry_polls_total{result=failed,dimension=" + spec.index() + "}");
			Log.warn("dimension poll failed", "dimension", spec.index(), "failures", count, "error", lastError);
		}
	}

	/**
	 * Polling policy: start from the configured tier, drift back towards it, speed up when the
	 * network is larger than planned and slow down when the server needs more than half the
	 * interval to answer. Everything is clamped, so a pathological reading cannot starve or
	 * hammer the server.
	 */
	private void adapt(long latencyMs, int observedTrains) {
		if (!config.getBool("network.adaptive")) {
			effectiveIntervalMs = config.pollingInterval();
			return;
		}
		final long base = config.pollingInterval();
		final long min = Math.max(200, config.getLong("network.polling_min_ms"));
		final long max = Math.max(min, config.getLong("network.polling_max_ms"));
		final int expected = config.getInt("network.max_trains");
		long next = effectiveIntervalMs <= 0 ? base : effectiveIntervalMs;

		if (latencyMs > next / 2) {
			next = (long) (next * 1.5);
		} else if (expected > 0 && observedTrains > expected) {
			next = (long) (next * 0.8);
		} else if (next > base) {
			next = Math.max(base, (long) (next * 0.9));
		} else if (next < base) {
			next = Math.min(base, (long) (next * 0.9) + 1);
		}
		effectiveIntervalMs = Math.max(min, Math.min(max, next));
		metrics.gauge("telemetry_interval_ms{dimension=" + spec.index() + "}", effectiveIntervalMs);
	}

	public long effectiveIntervalMs() {
		return Math.max(200, effectiveIntervalMs);
	}

	public Map<String, Object> health() {
		final long now = System.currentTimeMillis();
		final long age = lastSuccessAt.get() == 0 ? -1 : now - lastSuccessAt.get();
		final String state;
		if (lastSuccessAt.get() == 0) {
			state = failures.get() > 3 ? "FAILING" : "STARTING";
		} else if (failures.get() > 0 || age > 3 * effectiveIntervalMs) {
			state = age > Math.max(60_000L, 10 * effectiveIntervalMs) ? "FAILING" : "DEGRADED";
		} else {
			state = "OK";
		}
		final Map<String, Object> result = new LinkedHashMap<>();
		result.put("state", state);
		result.put("dimension", spec.id());
		result.put("index", spec.index());
		result.put("lastSuccessAt", lastSuccessAt.get());
		result.put("ageMillis", age);
		result.put("consecutiveFailures", failures.get());
		result.put("polls", polls.get());
		result.put("lastError", lastError);
		result.put("pollingIntervalMs", effectiveIntervalMs);
		return result;
	}

	public Map<String, Object> status() {
		final Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", spec.id());
		result.put("index", spec.index());
		result.put("health", health());
		result.put("source", activeSource() == null ? "none" : activeSource().name());
		result.put("bridgeConfigured", bridgeSource != null);
		result.put("world", snapshot == null ? Map.of() : snapshot.summary());
		result.put("compatibility", compatibility == null ? Map.of() : compatibility.toJson());
		result.put("dispatch", dispatcher == null ? Map.of() : dispatcher.status());
		result.put("bus", bus == null ? Map.of() : bus.describe());
		result.put("leases", leases.describe());
		return result;
	}

	public WorldSnapshot snapshot() {
		return snapshot;
	}

	public Dispatcher dispatcher() {
		return dispatcher;
	}

	public CommandBus bus() {
		return bus;
	}

	public LeaseTable leases() {
		return leases;
	}

	public CompatibilityProbe.Result compatibility() {
		return compatibility;
	}

	/** Observed vehicles, used by the service for its aggregate scale report. */
	public int observedVehicles() {
		final WorldSnapshot current = snapshot;
		return current == null ? 0 : current.vehicles.size();
	}

	public int observedStations() {
		final WorldSnapshot current = snapshot;
		return current == null ? 0 : current.stations.size();
	}

	public long lastSuccessAt() {
		return lastSuccessAt.get();
	}

	public String lastError() {
		return lastError;
	}
}
