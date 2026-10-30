package org.tsc.dispatch.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Startup and on-demand validation of the effective configuration.
 *
 * <p>Errors are conditions that make the service unsafe or meaningless (bad port, no enabled
 * dimension, unparsable bounds). Warnings are conditions that still allow operation but will
 * silently change behaviour (unknown strategy name, empty platform list, bridge mode that
 * cannot write). The service refuses to start on errors unless explicitly told otherwise.</p>
 */
public final class ConfigValidator {

	public record Issue(String severity, String field, String message) {

		public Map<String, Object> toJson() {
			final Map<String, Object> result = new LinkedHashMap<>();
			result.put("severity", severity);
			result.put("field", field);
			result.put("message", message);
			return result;
		}
	}

	private static final Set<String> BRIDGE_MODES = Set.of("auto", "bridge", "rcon", "observe");
	private static final Set<String> BRIDGE_FORMS = Set.of("http", "mod", "script");
	private static final Set<String> PLATFORM_DEFAULTS = Set.of("allow", "deny");
	private static final Set<String> FALLBACK_TRIGGERS = Set.of("bridge_offline", "bridge_reject", "command_timeout", "manual");

	private ConfigValidator() {
	}

	public static List<Issue> validate(Config config) {
		final List<Issue> issues = new ArrayList<>();
		port(issues, "admin.port", config.getInt("admin.port"));
		url(issues, "tsc.base_url", config.get("tsc.base_url"));
		url(issues, "bridge.base_url", config.get("bridge.base_url"));

		positive(issues, "tsc.timeout_ms", config.getLong("tsc.timeout_ms"));
		positive(issues, "tsc.poll_interval_ms", config.getLong("tsc.poll_interval_ms"));
		positive(issues, "tsc.static_poll_interval_ms", config.getLong("tsc.static_poll_interval_ms"));
		nonNegative(issues, "tsc.retry_count", config.getInt("tsc.retry_count"));
		positive(issues, "dispatch.command_ttl_seconds", config.getLong("dispatch.command_ttl_seconds"));
		positive(issues, "dispatch.lease_ttl_seconds", config.getLong("dispatch.lease_ttl_seconds"));
		nonNegative(issues, "dispatch.constraints.blocked_penalty_seconds", config.getLong("dispatch.constraints.blocked_penalty_seconds"));
		nonNegative(issues, "dispatch.constraints.depot_capacity_limit", config.getLong("dispatch.constraints.depot_capacity_limit"));
		nonNegative(issues, "dispatch.constraints.max_hold_seconds", config.getLong("dispatch.constraints.max_hold_seconds"));

		for (final String weight : List.of("travel", "occupancy", "wait", "depot", "deviation")) {
			final double value = config.weight(weight);
			if (value < 0) {
				issues.add(new Issue("ERROR", "dispatch.weights." + weight, "weight must be >= 0, got " + value));
			}
		}

		final String strategy = config.get("dispatch.strategy");
		if (strategy != null && !strategy.isBlank()) {
			boolean known = false;
			for (final Strategy candidate : Strategy.values()) {
				known |= candidate.name().equalsIgnoreCase(strategy.trim());
			}
			if (!known) {
				issues.add(new Issue("WARN", "dispatch.strategy", "unknown strategy '" + strategy + "', falling back to balanced"));
			}
		}

		final String bridgeMode = config.get("bridge.mode").toLowerCase(Locale.ROOT);
		if (!BRIDGE_MODES.contains(bridgeMode)) {
			issues.add(new Issue("ERROR", "bridge.mode", "must be one of " + BRIDGE_MODES));
		}
		final String bridgeForm = config.get("bridge.form").toLowerCase(Locale.ROOT);
		if (!BRIDGE_FORMS.contains(bridgeForm)) {
			issues.add(new Issue("WARN", "bridge.form", "unknown form '" + bridgeForm + "' (informational only)"));
		}
		if (config.getInt("bridge.protocol") != 1) {
			issues.add(new Issue("WARN", "bridge.protocol", "service speaks protocol 1; the bridge will be probed but may refuse"));
		}
		if (config.getBool("bridge.enabled") && !"observe".equals(bridgeMode) && "bridge".equalsIgnoreCase(config.get("actuator.mode"))
			&& !config.rconEnabled()) {
			issues.add(new Issue("WARN", "actuator.mode", "mode=bridge but no bridge is guaranteed and RCON is disabled"));
		}

		if (config.getBool("rcon.enabled") && config.get("rcon.password").isEmpty()) {
			issues.add(new Issue("WARN", "rcon.password", "RCON enabled but no password set; RCON will stay disabled"));
		}
		if (config.rconEnabled()) {
			port(issues, "rcon.port", config.getInt("rcon.port"));
		}
		final Set<String> triggers = config.rconFallbackOn();
		for (final String trigger : triggers) {
			if (!FALLBACK_TRIGGERS.contains(trigger)) {
				issues.add(new Issue("WARN", "rcon.fallback_on", "unknown trigger '" + trigger + "' (allowed: " + FALLBACK_TRIGGERS + ")"));
			}
		}

		final List<DimensionSpec> dimensions = config.dimensions();
		final Set<Integer> indexes = new LinkedHashSet<>();
		final Set<String> ids = new LinkedHashSet<>();
		boolean anyEnabled = false;
		for (final DimensionSpec dimension : dimensions) {
			if (dimension.index() < 0) {
				issues.add(new Issue("ERROR", "dimensions", "negative index for " + dimension.id()));
			}
			if (!indexes.add(dimension.index())) {
				issues.add(new Issue("ERROR", "dimensions", "duplicate index " + dimension.index()));
			}
			if (!ids.add(dimension.id())) {
				issues.add(new Issue("ERROR", "dimensions", "duplicate id " + dimension.id()));
			}
			anyEnabled |= dimension.enabled();
		}
		if (!anyEnabled) {
			issues.add(new Issue("ERROR", "dimensions", "no enabled dimension"));
		}

		final String platformDefault = config.get("platform_allow.default").toLowerCase(Locale.ROOT);
		if (!PLATFORM_DEFAULTS.contains(platformDefault)) {
			issues.add(new Issue("ERROR", "platform_allow.default", "must be allow or deny"));
		}
		for (final PlatformRule rule : config.platformPolicy().rules()) {
			if (rule.platforms().isEmpty() && !rule.deny()) {
				issues.add(new Issue("WARN", "platform_allow.rules", "empty allow-list for route '" + rule.route() + "' at station '" + rule.station() + "'"));
			}
		}

		final String declared = config.get("tsc.version.declared");
		final String min = config.get("tsc.version.min");
		final String max = config.get("tsc.version.max");
		if (!min.isBlank() && !max.isBlank() && Semver.compare(min, max) > 0) {
			issues.add(new Issue("ERROR", "tsc.version", "min " + min + " is greater than max " + max));
		}
		if (!declared.isBlank() && Semver.parse(declared) == null) {
			issues.add(new Issue("WARN", "tsc.version.declared", "cannot parse '" + declared + "'"));
		}

		if (config.getLong("network.polling_min_ms") > config.getLong("network.polling_max_ms")) {
			issues.add(new Issue("ERROR", "network.polling", "min interval is greater than max interval"));
		}
		for (final ScaleTier tier : config.scaleTiers()) {
			if (tier.maxTrains() <= 0) {
				issues.add(new Issue("WARN", "network_scale.tiers", "tier with max_trains <= 0 is ignored"));
			}
		}

		return issues;
	}

	public static boolean hasErrors(List<Issue> issues) {
		return issues.stream().anyMatch(issue -> "ERROR".equals(issue.severity()));
	}

	public static Map<String, Object> report(List<Issue> issues) {
		final Map<String, Object> result = new LinkedHashMap<>();
		final List<Object> encoded = new ArrayList<>();
		issues.forEach(issue -> encoded.add(issue.toJson()));
		result.put("ok", !hasErrors(issues));
		result.put("errors", issues.stream().filter(issue -> "ERROR".equals(issue.severity())).count());
		result.put("warnings", issues.stream().filter(issue -> "WARN".equals(issue.severity())).count());
		result.put("issues", encoded);
		return result;
	}

	private static void port(List<Issue> issues, String field, int value) {
		if (value < 1 || value > 65535) {
			issues.add(new Issue("ERROR", field, "port out of range: " + value));
		}
	}

	private static void url(List<Issue> issues, String field, String value) {
		if (value == null || !(value.startsWith("http://") || value.startsWith("https://"))) {
			issues.add(new Issue("ERROR", field, "must start with http:// or https://, got " + value));
		}
	}

	private static void positive(List<Issue> issues, String field, long value) {
		if (value <= 0) {
			issues.add(new Issue("ERROR", field, "must be > 0, got " + value));
		}
	}

	private static void nonNegative(List<Issue> issues, String field, long value) {
		if (value < 0) {
			issues.add(new Issue("ERROR", field, "must be >= 0, got " + value));
		}
	}
}
