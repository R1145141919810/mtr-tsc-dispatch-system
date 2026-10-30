package org.tsc.dispatch;

import org.tsc.dispatch.actuator.Actuator;
import org.tsc.dispatch.actuator.BridgeActuator;
import org.tsc.dispatch.actuator.FailoverActuator;
import org.tsc.dispatch.actuator.RconActuator;
import org.tsc.dispatch.admin.AdminServer;
import org.tsc.dispatch.config.Config;
import org.tsc.dispatch.config.ConfigValidator;
import org.tsc.dispatch.config.DimensionSpec;
import org.tsc.dispatch.dispatch.CommandBus;
import org.tsc.dispatch.dispatch.Dispatcher;
import org.tsc.dispatch.model.WorldSnapshot;
import org.tsc.dispatch.runtime.DimensionRuntime;
import org.tsc.dispatch.source.BridgeSource;
import org.tsc.dispatch.source.HttpJson;
import org.tsc.dispatch.source.PortDiscovery;
import org.tsc.dispatch.util.Json;
import org.tsc.dispatch.util.Log;
import org.tsc.dispatch.util.Metrics;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Process-level orchestration: validate, probe channels, spawn one isolated runtime per
 * enabled dimension, serve the management API and own the polling loop.
 *
 * <p>Startup is fail-closed. A configuration error stops the service with a readable report
 * instead of running on defaults that might command trains with wrong premises. Runtime
 * problems (bridge down, port moved, telemetry stale) degrade instead: the service keeps
 * answering and keeps deciding, but stops claiming it can act.</p>
 */
public final class Service implements AutoCloseable {

	private final Config config;
	private final Metrics metrics = new Metrics();

	private HttpJson http;
	private HttpJson bridgeHttp;
	private BridgeSource bridge;
	private FailoverActuator failover;
	private List<DimensionRuntime> runtimes = List.of();
	private AdminServer admin;
	private ScheduledExecutorService scheduler;
	private Map<String, Object> validation = Map.of();
	private final List<ConfigValidator.Issue> issues = new ArrayList<>();
	private PortDiscovery.Result port;
	private volatile boolean running;
	private volatile long bridgeCheckedAt;

	public Service(Config config) {
		this.config = config;
	}

	public void start() throws Exception {
		Log.configure(Log.Level.valueOf(config.get("log.level", "INFO").toUpperCase(java.util.Locale.ROOT)),
			config.get("log.file").isEmpty() ? null : Path.of(config.get("log.file")));

		Log.info("starting dispatch service", "version", Config.VERSION,
			"config", config.source() == null ? "defaults" : config.source());

		issues.addAll(ConfigValidator.validate(config));
		validation = ConfigValidator.report(issues);
		issues.forEach(issue -> {
			if ("ERROR".equals(issue.severity())) {
				Log.error("config error", "field", issue.field(), "message", issue.message());
			} else if ("WARN".equals(issue.severity())) {
				Log.warn("config warning", "field", issue.field(), "message", issue.message());
			}
		});
		if (ConfigValidator.hasErrors(issues)) {
			throw new IllegalStateException("configuration rejected: " + issues.stream()
				.filter(issue -> "ERROR".equals(issue.severity()))
				.map(issue -> issue.field() + ": " + issue.message())
				.toList());
		}

		if (config.getBool("tsc.version.strict") && config.get("tsc.version.declared").isBlank()) {
			throw new IllegalStateException("tsc.version.strict is set but tsc.version.declared is empty");
		}

		http = new HttpJson(Duration.ofMillis(Math.max(200, config.getLong("tsc.timeout_ms"))),
			Math.max(0, config.getInt("tsc.retry_count")), "tsc-dispatch/" + Config.VERSION);

		final List<DimensionSpec> configured = config.enabledDimensions();
		port = PortDiscovery.resolve(config, http, configured.get(0).index());

		resolveChannels();
		// adoptBridgeCapabilities() may rewrite dimension indices by id, so the runtime list must
		// be read *after* channels are resolved - otherwise the service logs the remap but keeps
		// polling the old index (and therefore the wrong dimension).
		final List<DimensionSpec> enabled = config.enabledDimensions();
		validation = ConfigValidator.report(issues);

		final List<DimensionRuntime> created = new ArrayList<>();
		for (final DimensionSpec dimension : enabled) {
			final DimensionRuntime runtime = new DimensionRuntime(dimension, config, metrics, http, bridgeHttp, failover,
				this::bridgeUsable, port.baseUrl());
			runtime.start();
			created.add(runtime);
		}
		runtimes = List.copyOf(created);

		if (config.getBool("admin.enabled")) {
			admin = new AdminServer(config, metrics, () -> runtimes, this::health, this::channels, () -> validation);
			admin.start();
		}

		scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			final Thread thread = new Thread(runnable, "dispatch-loop");
			thread.setDaemon(true);
			return thread;
		});
		running = true;
		scheduler.schedule(this::pollAll, 0, TimeUnit.MILLISECONDS);
		Log.info("dispatch loop started", "dimensions", runtimes.size(), "baseUrl", port.baseUrl(),
			"actuator", failover.status().get("active"));
	}

	/** Probes the bridge and assembles the failover chain. Never throws on a missing bridge. */
	private void resolveChannels() {
		final Actuator rcon = config.rconEnabled() ? new RconActuator(config) : null;
		bridgeHttp = new HttpJson(Duration.ofMillis(Math.max(200, config.getLong("bridge.timeout_ms"))), 1,
			"tsc-dispatch/" + Config.VERSION, config.get("bridge.auth_token"));
		if (config.bridgeEnabled()) {
			bridge = new BridgeSource(config, bridgeHttp, config.enabledDimensions().get(0).index());
			bridge.refreshHealth();
			if (bridge.probe() && meetsRequirements(bridge)) {
				Log.info("control bridge online", "url", config.get("bridge.base_url"), "features", bridge.features(),
					"ready", bridge.ready(), "health", bridge.health());
			} else {
				Log.warn("control bridge unavailable at startup", "url", config.get("bridge.base_url"),
					"ready", bridge.ready(), "reason", bridge.health().get("lastError"));
			}
		}
		final int primaryDimension = config.enabledDimensions().get(0).index();
		// Built even when the bridge is not ready yet: capabilities are resolved per call, so a
		// bridge that finishes loading later is picked up without restarting the service.
		final Actuator bridgeActuator = bridge != null
			? new BridgeActuator(config, bridgeHttp, bridge::features, primaryDimension)
			: null;
		failover = new FailoverActuator(bridgeActuator, rcon, config.rconFallbackOn(), metrics);
		if (bridgeActuator == null) {
			failover.markBridgeOnline(false, "bridge not usable");
		}
		if (rcon == null) {
			Log.warn("RCON fallback disabled", "reason", config.getBool("rcon.enabled") ? "empty password" : "rcon.enabled=false");
		}
		if (bridge != null && bridge.protocol() >= 1) {
			adoptBridgeCapabilities();
		}
	}

	/**
	 * Consumes what the bridge says about itself.
	 *
	 * <p>The dimension order is a property of the save, not of the mod: the same build produced
	 * overworld/the_end/the_nether on one world and overworld/the_nether/the_end on the next. A
	 * silently wrong index means commands for one dimension land in another, so a mismatch is
	 * reported as a configuration error - and is fatal when the bridge is mandatory.</p>
	 */
	private void adoptBridgeCapabilities() {
		final long floor = bridge.minPollIntervalMs();
		if (floor > config.getLong("network.polling_min_ms")) {
			config.override("network.polling_min_ms", String.valueOf(floor));
			Log.info("polling floor raised by bridge", "minPollIntervalMs", floor);
		}

		final List<String> ids = bridge.dimensionIds();
		if (ids.isEmpty()) {
			Log.debug("bridge does not report dimensions; skipping index/id self-check");
			return;
		}
		// The host builds its dimension list from world-load order, which is not stable across
		// restarts (two runs of the same save produced the_end/the_nether in opposite order).
		// Ids are stable, indices are not: remap by id when the id is uniquely present, and only
		// treat "id not found" as an error.
		// The bridge reports Mojang resource locations ({@code minecraft/overworld}); the
		// configuration template uses the colon form ({@code minecraft:overworld}). Both name the
		// same world, so compare with a normalised separator - otherwise every configured
		// dimension looks "missing" and the index remap silently never happens.
		final List<String> normalised = new ArrayList<>(ids.size());
		for (final String id : ids) {
			normalised.add(normaliseDimensionId(id));
		}
		final List<DimensionSpec> remapped = new ArrayList<>();
		boolean changed = false;
		boolean missing = false;
		for (final DimensionSpec spec : config.dimensions()) {
			final int found = normalised.indexOf(normaliseDimensionId(spec.id()));
			if (found < 0) {
				remapped.add(spec);
				if (spec.enabled()) {
					issues.add(new ConfigValidator.Issue("ERROR", "dimensions",
						"configured dimension " + spec.id() + " is not in the bridge list " + ids));
					missing = true;
				}
			} else if (found != spec.index()) {
				remapped.add(new DimensionSpec(spec.id(), found, spec.enabled()));
				changed = true;
				Log.warn("dimension index remapped by id", "id", spec.id(), "configured", spec.index(), "actual", found);
			} else {
				remapped.add(spec);
			}
		}
		if (changed) {
			config.withDimensions(remapped);
			issues.add(new ConfigValidator.Issue("WARN", "dimensions",
				"dimension indices remapped by id; update dispatch.yml to " + remapped));
		}
		if (missing) {
			final String message = "a configured (enabled) dimension is not present on the bridge (bridge: " + ids + "); fix dimensions[] in dispatch.yml";
			if ("bridge".equalsIgnoreCase(config.get("actuator.mode"))) {
				throw new IllegalStateException(message);
			}
			Log.error(message);
		}
	}

	/** {@code minecraft:overworld} (config) and {@code minecraft/overworld} (bridge) are one world. */
	private static String normaliseDimensionId(String id) {
		return id == null ? "" : id.replace(':', '/');
	}

	private boolean meetsRequirements(BridgeSource candidate) {
		if (candidate == null || candidate.protocol() < 1) {
			return false;
		}
		// Up but no world loaded: answering, yet every command would be pointless.
		if (!candidate.ready()) {
			Log.debug("control bridge not ready (no world loaded)");
			return false;
		}
		if (config.getBool("bridge.require_vehicle_telemetry") && !candidate.vehicleTelemetry()) {
			return false;
		}
		return candidate.features().containsAll(config.bridgeFeaturesRequired());
	}

	private boolean bridgeUsable() {
		return failover != null && failover.bridgeOnline();
	}

	private void pollAll() {
		if (!running) {
			return;
		}
		final long started = System.currentTimeMillis();
		heartbeat();
		for (final DimensionRuntime runtime : runtimes) {
			try {
				runtime.tick();
			} catch (Exception exception) {
				Log.error("dimension tick failed", exception, "dimension", runtime.index());
			}
		}
		final long interval = runtimes.stream().mapToLong(DimensionRuntime::effectiveIntervalMs).min().orElse(2000);
		final long elapsed = System.currentTimeMillis() - started;
		final long delay = Math.max(50, interval - elapsed);
		try {
			scheduler.schedule(this::pollAll, delay, TimeUnit.MILLISECONDS);
		} catch (Exception exception) {
			Log.debug("scheduler stopped", "error", exception.getMessage());
		}
	}

	private void heartbeat() {
		if (bridge == null || failover == null) {
			return;
		}
		final long now = System.currentTimeMillis();
		if (now - bridgeCheckedAt < Math.max(1000, config.getLong("bridge.probe_interval_ms"))) {
			return;
		}
		bridgeCheckedAt = now;
		bridge.refreshHealth();
		final boolean ok = bridge.probe() && meetsRequirements(bridge);
		failover.markBridgeOnline(ok, ok ? "probe ok" : "probe failed");
		metrics.gauge("bridge_online", ok ? 1 : 0);
		metrics.gauge("bridge_pending_intents", Json.number(bridge.health(), "pendingIntents", 0));
		metrics.gauge("bridge_deferred_total", Json.number(bridge.health(), "deferred", 0));
	}

	public Map<String, Object> health() {
		final Map<String, Object> result = new LinkedHashMap<>();
		String worst = "OK";
		final List<Object> dimensions = new ArrayList<>();
		long lastSuccess = 0;
		for (final DimensionRuntime runtime : runtimes) {
			final Map<String, Object> health = runtime.health();
			dimensions.add(health);
			lastSuccess = Math.max(lastSuccess, runtime.lastSuccessAt());
			final String state = String.valueOf(health.get("state"));
			if (severity(state) > severity(worst)) {
				worst = state;
			}
		}
		result.put("state", worst);
		result.put("dimensions", dimensions);
		result.put("lastSuccessAt", lastSuccess);
		result.put("bridgeOnline", bridgeUsable());
		result.put("actuator", failover == null ? "none" : failover.status().get("active"));
		result.put("baseUrl", port == null ? config.get("tsc.base_url") : port.baseUrl());
		result.put("configuredPortMatched", port != null && port.discovered());
		return result;
	}

	private static int severity(String state) {
		return switch (state) {
			case "FAILING" -> 3;
			case "DEGRADED" -> 2;
			case "STARTING" -> 1;
			default -> 0;
		};
	}

	public Map<String, Object> channels() {
		final Map<String, Object> result = new LinkedHashMap<>();
		result.put("actuator", failover == null ? Map.of() : failover.status());
		result.put("bridge", bridge == null ? Map.of("configured", false) : bridge.describe());
		result.put("rcon", Map.of("configured", config.rconEnabled(), "host", config.get("rcon.host"), "port", config.getInt("rcon.port")));
		result.put("port", port == null ? Map.of() : port.toJson());
		return result;
	}

	public Map<String, Object> validation() {
		return validation;
	}

	public List<DimensionRuntime> runtimes() {
		return runtimes;
	}

	public DimensionRuntime primary() {
		return runtimes.isEmpty() ? null : runtimes.get(0);
	}

	public WorldSnapshot snapshot() {
		return primary() == null ? null : primary().snapshot();
	}

	public Dispatcher dispatcher() {
		return primary() == null ? null : primary().dispatcher();
	}

	public CommandBus bus() {
		return primary() == null ? null : primary().bus();
	}

	public Metrics metrics() {
		return metrics;
	}

	public Config config() {
		return config;
	}

	@Override
	public void close() {
		running = false;
		if (scheduler != null) {
			scheduler.shutdownNow();
		}
		if (admin != null) {
			admin.close();
		}
		if (failover != null) {
			failover.close();
		}
		if (http != null) {
			http.close();
		}
		if (bridgeHttp != null) {
			bridgeHttp.close();
		}
		runtimes.forEach(runtime -> {
			if (runtime.bus() != null) {
				runtime.bus().persist();
			}
		});
	}
}