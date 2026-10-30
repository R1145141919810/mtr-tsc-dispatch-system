package org.tsc.dispatch.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Effective configuration.
 *
 * <p>One file, {@code config/dispatch.yml}, drives the whole service. YAML nesting is flattened
 * into dotted keys ({@code tsc.timeout_ms}) so the rest of the code can read single values
 * without knowing the document shape, while the three genuinely structural settings
 * (dimensions, platform rules, scale tiers) stay as typed values.</p>
 *
 * <p>A legacy {@code .properties} file is still accepted: it produces the same flat key space
 * and leaves the structural settings at their defaults.</p>
 */
public final class Config {

	public static final String VERSION = "1.1.0";

	private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

	static {
		put("service.name", "tsc-dispatch");
		put("admin.enabled", "true");
		put("admin.host", "127.0.0.1");
		put("admin.port", "8787");
		put("admin.token", "");

		put("tsc.base_url", "http://127.0.0.1:8888");
		put("tsc.timeout_ms", "2500");
		put("tsc.retry_count", "2");
		put("tsc.dimension", "0");
		put("tsc.poll_interval_ms", "2000");
		put("tsc.static_poll_interval_ms", "30000");
		put("tsc.arrivals_max_per_platform", "4");
		put("tsc.arrivals_max_total", "200");
		put("tsc.version.declared", "");
		put("tsc.version.min", "4.0.0");
		put("tsc.version.max", "");
		put("tsc.version.strict", "false");
		put("tsc.port_discovery.enabled", "true");
		put("tsc.port_discovery.range_start", "8888");
		put("tsc.port_discovery.range_end", "8898");

		put("bridge.enabled", "true");
		put("bridge.mode", "auto");
		put("bridge.form", "http");
		put("bridge.base_url", "http://127.0.0.1:8889");
		put("bridge.protocol", "1");
		put("bridge.timeout_ms", "2000");
		put("bridge.probe_interval_ms", "15000");
		put("bridge.offline_after_failures", "3");
		put("bridge.auth_token", "");
		put("bridge.require_vehicle_telemetry", "true");
		put("bridge.features_required", "set_platform");

		put("rcon.enabled", "true");
		put("rcon.host", "127.0.0.1");
		put("rcon.port", "25575");
		put("rcon.password", "");
		put("rcon.timeout_ms", "3000");
		put("rcon.fallback_on", "bridge_offline,bridge_reject,command_timeout");
		put("rcon.commands.refresh_depot_routes", "mtr generatePath {depot}");
		put("rcon.commands.instant_deploy", "mtr instantDeploy {depot}");
		put("rcon.commands.clear_depot", "mtr clearVehicles {depot}");

		put("actuator.mode", "auto");
		put("actuator.allow_advisory_only", "true");

		put("network.adaptive", "true");
		put("network.max_trains", "0");
		put("network.total_stations", "0");
		put("network.total_platforms", "0");
		put("network.total_routes", "0");
		put("network.total_depots", "0");
		put("network.polling_min_ms", "500");
		put("network.polling_max_ms", "10000");

		put("dispatch.strategy", "balanced");
		// Blank means "take the value from the strategy preset"; an explicit number always wins.
		put("dispatch.weights.travel", "");
		put("dispatch.weights.occupancy", "");
		put("dispatch.weights.wait", "");
		put("dispatch.weights.depot", "");
		put("dispatch.weights.deviation", "");
		put("dispatch.constraints.depot_capacity_limit", "0");
		put("dispatch.constraints.max_hold_seconds", "0");
		put("dispatch.constraints.blocked_penalty_seconds", "900");
		put("dispatch.dedup_window_seconds", "600");
		put("dispatch.command_ttl_seconds", "45");
		put("dispatch.max_attempts", "3");
		put("dispatch.retry_backoff_ms", "250");
		put("dispatch.lease_ttl_seconds", "120");

		put("trigger.approach_lead_seconds", "120");
		put("trigger.platform_headway_seconds", "90");
		put("trigger.cooldown_seconds", "30");
		put("trigger.return_after_seconds", "5400");
		put("trigger.return_at_local_time", "");

		put("planner.estimate_speed_mps", "18.0");
		put("planner.depot_access_seconds", "150");
		put("catalogue.platforms", "");
		put("platform_allow.default", "allow");

		put("state.dir", "state");
		put("log.level", "INFO");
		put("log.file", "");
	}

	private final Map<String, String> scalars = new LinkedHashMap<>();
	private final Map<String, String> overrides = new LinkedHashMap<>();
	private Map<String, Object> root = new LinkedHashMap<>();
	private Path source;
	private List<DimensionSpec> dimensions;
	private PlatformPolicy platformPolicy = PlatformPolicy.allowAll();
	private List<ScaleTier> scaleTiers = List.of();

	private Config() {
		parseDimensions();
		parseScaleTiers();
	}

	public static Config defaults() {
		return new Config();
	}

	public static Config load(Path path) throws IOException {
		final Config config = new Config();
		config.source = path;
		if (path == null || !Files.exists(path)) {
			return config;
		}
		final String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		if (name.endsWith(".yml") || name.endsWith(".yaml")) {
			config.loadYaml(Files.readString(path, StandardCharsets.UTF_8));
		} else {
			config.loadProperties(path);
		}
		return config;
	}

	private void loadYaml(String text) {
		root = Yaml.parse(text);
		flatten("", root);
		parseDimensions();
		parsePlatformPolicy();
		parseScaleTiers();
	}

	private void loadProperties(Path path) throws IOException {
		final Properties properties = new Properties();
		try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		properties.stringPropertyNames().forEach(key -> scalars.put(normalize(key), properties.getProperty(key).trim()));
		parseDimensions();
	}

	@SuppressWarnings("unchecked")
	private void flatten(String prefix, Object node) {
		if (node instanceof Map<?, ?> map) {
			map.forEach((key, value) -> flatten(prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key, value));
		} else if (node instanceof List<?> list) {
			final StringBuilder joined = new StringBuilder();
			for (final Object element : list) {
				if (element instanceof Map || element instanceof List) {
					return; // structural list: kept in the root, never flattened
				}
				if (!joined.isEmpty()) {
					joined.append(',');
				}
				joined.append(element);
			}
			if (!prefix.isEmpty()) {
				scalars.put(normalize(prefix), joined.toString());
			}
		} else {
			scalars.put(normalize(prefix), node == null ? "" : String.valueOf(node));
		}
	}

	// ---------------------------------------------------------------- structure

	private void parseDimensions() {
		final List<DimensionSpec> parsed = new ArrayList<>();
		for (final Map<String, Object> item : mapsAt("dimensions")) {
			final String id = String.valueOf(item.getOrDefault("id", ""));
			final int index = toInt(item.get("index"), parsed.size());
			final boolean enabled = !"false".equalsIgnoreCase(String.valueOf(item.getOrDefault("enabled", "true")));
			if (!id.isBlank()) {
				parsed.add(new DimensionSpec(id, index, enabled));
			}
		}
		if (parsed.isEmpty()) {
			parsed.add(new DimensionSpec("minecraft:overworld", getInt("tsc.dimension"), true));
		}
		dimensions = List.copyOf(parsed);
	}

	private void parsePlatformPolicy() {
		final PlatformPolicy.DefaultPolicy defaultPolicy = PlatformPolicy.DefaultPolicy.parse(get("platform_allow.default"));
		final List<PlatformRule> rules = new ArrayList<>();
		for (final Map<String, Object> item : mapsAt("platform_allow.rules")) {
			final String route = String.valueOf(item.getOrDefault("route", "*"));
			final String station = String.valueOf(item.getOrDefault("station", "*"));
			final List<String> platforms = new ArrayList<>();
			final Object rawPlatforms = item.get("platforms");
			if (rawPlatforms instanceof List<?> list) {
				list.forEach(platform -> platforms.add(String.valueOf(platform)));
			} else if (rawPlatforms != null && !String.valueOf(rawPlatforms).isBlank()) {
				for (final String platform : String.valueOf(rawPlatforms).split(",")) {
					if (!platform.isBlank()) {
						platforms.add(platform.trim());
					}
				}
			}
			final boolean deny = "true".equalsIgnoreCase(String.valueOf(item.getOrDefault("deny", "false")));
			rules.add(new PlatformRule(route, station, platforms, deny));
		}
		platformPolicy = new PlatformPolicy(defaultPolicy, rules);
	}

	private void parseScaleTiers() {
		final List<ScaleTier> tiers = new ArrayList<>();
		for (final Map<String, Object> item : mapsAt("network_scale.tiers")) {
			final int maxTrains = toInt(item.get("max_trains"), Integer.MAX_VALUE);
			final long interval = toLong(item.get("polling_interval_ms"), getLong("tsc.poll_interval_ms"));
			tiers.add(new ScaleTier(maxTrains, Math.max(200, interval)));
		}
		if (tiers.isEmpty()) {
			// Sensible defaults when the operator did not define tiers: the service still adapts.
			tiers.add(new ScaleTier(10, 1000));
			tiers.add(new ScaleTier(40, 2000));
			tiers.add(new ScaleTier(Integer.MAX_VALUE, 4000));
		}
		tiers.sort(java.util.Comparator.comparingInt(ScaleTier::maxTrains));
		scaleTiers = List.copyOf(tiers);
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> mapsAt(String path) {
		Object node = root;
		for (final String segment : path.split("\\.")) {
			if (!(node instanceof Map<?, ?> map)) {
				return List.of();
			}
			node = map.get(segment);
		}
		if (!(node instanceof List<?> list)) {
			return List.of();
		}
		final List<Map<String, Object>> result = new ArrayList<>();
		for (final Object element : list) {
			if (element instanceof Map<?, ?> map) {
				result.add((Map<String, Object>) map);
			}
		}
		return result;
	}

	// ---------------------------------------------------------------- accessors

	public List<DimensionSpec> dimensions() {
		return dimensions;
	}

	public List<DimensionSpec> enabledDimensions() {
		final List<DimensionSpec> enabled = new ArrayList<>();
		dimensions.forEach(dimension -> {
			if (dimension.enabled()) {
				enabled.add(dimension);
			}
		});
		return enabled.isEmpty() ? List.of(new DimensionSpec("minecraft:overworld", getInt("tsc.dimension"), true)) : enabled;
	}

	public PlatformPolicy platformPolicy() {
		return platformPolicy;
	}

	/** Replaces the platform allow-set at runtime (config file, bridge or tests). */
	public Config withPlatformPolicy(PlatformPolicy policy) {
		this.platformPolicy = policy == null ? PlatformPolicy.allowAll() : policy;
		return this;
	}

	/** Replaces the dimension list (config file, tests). */
	public Config withDimensions(List<DimensionSpec> specs) {
		if (specs != null && !specs.isEmpty()) {
			this.dimensions = List.copyOf(specs);
		}
		return this;
	}

	public List<ScaleTier> scaleTiers() {
		return scaleTiers;
	}

	public Strategy strategy() {
		return Strategy.parse(get("dispatch.strategy"));
	}

/** Explicit weight wins, then the legacy planner key, then the strategy preset. */
	public double weight(String name) {
		final String explicit = find("dispatch.weights." + name);
		if (explicit != null && !explicit.isBlank()) {
			return parseDouble(explicit, 1.0);
		}
		final String legacy = find("planner.weight." + name);
		if (legacy != null && !legacy.isBlank()) {
			return parseDouble(legacy, 1.0);
		}
		return strategy().presetWeights.getOrDefault(name, 1.0);
	}

	public double routePriority(String routeHex, String routeNumber) {
		final String byNumber = routeNumber == null ? null : find("dispatch.constraints.route_priority." + routeNumber);
		if (byNumber != null) {
			return parseDouble(byNumber, 1.0);
		}
		final String byHex = routeHex == null ? null : find("dispatch.constraints.route_priority." + routeHex);
		if (byHex != null) {
			return parseDouble(byHex, 1.0);
		}
		return 1.0;
	}

	public int depotCapacity(String depotName) {
		final String specific = find("dispatch.constraints.depot_capacity." + depotName);
		if (specific != null) {
			return (int) parseDouble(specific, 0);
		}
		return getInt("dispatch.constraints.depot_capacity_limit");
	}

	public Set<String> rconFallbackOn() {
		final Set<String> triggers = new LinkedHashSet<>();
		getList("rcon.fallback_on").forEach(trigger -> triggers.add(trigger.toLowerCase(Locale.ROOT)));
		return triggers;
	}

	public List<String> bridgeFeaturesRequired() {
		return getList("bridge.features_required");
	}

	public boolean bridgeEnabled() {
		return getBool("bridge.enabled") && !"observe".equalsIgnoreCase(get("bridge.mode"));
	}

	public boolean rconEnabled() {
		return getBool("rcon.enabled") && !get("rcon.password").isEmpty();
	}

	public String rconCommand(String kind, String depot) {
		final String template = get("rcon.commands." + kind);
		return template == null ? "" : template.replace("{depot}", depot);
	}

	public long pollingInterval() {
		final long base = getLong("tsc.poll_interval_ms");
		if (!getBool("network.adaptive") || scaleTiers.isEmpty()) {
			return Math.max(200, base);
		}
		final int expected = getInt("network.max_trains");
		for (final ScaleTier tier : scaleTiers) {
			if (expected <= 0 || expected <= tier.maxTrains()) {
				return clamp(tier.pollingIntervalMs());
			}
		}
		return clamp(scaleTiers.get(scaleTiers.size() - 1).pollingIntervalMs());
	}

	private long clamp(long interval) {
		final long min = getLong("network.polling_min_ms");
		final long max = getLong("network.polling_max_ms");
		return Math.max(min, Math.min(max, interval));
	}

	// ---------------------------------------------------------------- flat keys

	public String get(String key) {
		final String found = find(key);
		return found != null ? found : DEFAULTS.get(normalize(key));
	}

	private String find(String key) {
		final String normalized = normalize(key);
		if (overrides.containsKey(normalized)) {
			return overrides.get(normalized);
		}
		return scalars.get(normalized);
	}

	public String get(String key, String fallback) {
		final String value = get(key);
		return value == null ? fallback : value;
	}

	public int getInt(String key) {
		return (int) parseDouble(get(key), 0);
	}

	public long getLong(String key) {
		return (long) parseDouble(get(key), 0);
	}

	public double getDouble(String key) {
		return parseDouble(get(key), 0);
	}

	public boolean getBool(String key) {
		return Boolean.parseBoolean(get(key, "false").trim());
	}

	public List<String> getList(String key) {
		final String raw = get(key, "");
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		final List<String> result = new ArrayList<>();
		for (final String part : raw.split(",")) {
			if (!part.isBlank()) {
				result.add(part.trim());
			}
		}
		return result;
	}

	public boolean override(String key, String value) {
		final String normalized = normalize(key);
		if (!DEFAULTS.containsKey(normalized) && !scalars.containsKey(normalized)) {
			return false;
		}
		overrides.put(normalized, value);
		return true;
	}

	public Config withOverrides(Map<String, String> values) {
		values.forEach(this::override);
		return this;
	}

	public Set<String> knownKeys() {
		final Set<String> keys = new LinkedHashSet<>(DEFAULTS.keySet());
		keys.addAll(scalars.keySet());
		return keys;
	}

	public Path source() {
		return source;
	}

	public Map<String, Object> describe() {
		final Map<String, Object> result = new LinkedHashMap<>();
		final Set<String> keys = new LinkedHashSet<>(DEFAULTS.keySet());
		keys.addAll(scalars.keySet());
		keys.forEach(key -> {
			final String value = get(key);
			result.put(key, isSecret(key) && value != null && !value.isEmpty() ? "***" : value);
		});
		result.put("_dimensions", dimensions.stream().map(dimension -> {
			final Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", dimension.id());
			item.put("index", dimension.index());
			item.put("enabled", dimension.enabled());
			return item;
		}).toList());
		result.put("_platform_allow", platformPolicy.describe());
		result.put("_scale_tiers", scaleTiers);
		result.put("_strategy", strategy().describe());
		return result;
	}

	public static boolean isSecret(String key) {
		final String normalized = key.toLowerCase(Locale.ROOT);
		return normalized.contains("password") || normalized.contains("token");
	}

	// ---------------------------------------------------------------- helpers

	public static String template() {
		return ConfigTemplate.YAML;
	}

	public static void writeTemplate(Path path) throws IOException {
		if (path.getParent() != null) {
			Files.createDirectories(path.getParent());
		}
		Files.writeString(path, template(), StandardCharsets.UTF_8);
	}

	public static String normalize(String key) {
		return key == null ? "" : key.trim().replace('-', '_');
	}

	private static double parseDouble(String value, double fallback) {
		if (value == null) {
			return fallback;
		}
		try {
			return Double.parseDouble(value.trim());
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static int toInt(Object value, int fallback) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		try {
			return Integer.parseInt(String.valueOf(value));
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static long toLong(Object value, long fallback) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		try {
			return Long.parseLong(String.valueOf(value));
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static void put(String key, String value) {
		DEFAULTS.put(normalize(key), value);
	}
}
