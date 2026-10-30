package org.tsc.dispatch.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scheduling objective presets. A preset is just a starting point for the cost weights; any
 * weight written explicitly in the configuration overrides the preset, which keeps "pick a
 * strategy" and "tune one number" from fighting each other.
 */
public enum Strategy {

	/** Punishes lateness hardest; trains keep their slot even if others wait. */
	ON_TIME(Map.of("travel", 1.0, "occupancy", 0.8, "wait", 0.25, "depot", 0.3, "deviation", 1.0)),
	/** Punishes other trains' waiting hardest; platform conflicts resolve faster. */
	MIN_WAITING(Map.of("travel", 1.0, "occupancy", 1.2, "wait", 1.4, "depot", 0.5, "deviation", 0.1)),
	/** Middle ground, the default. */
	BALANCED(Map.of("travel", 1.0, "occupancy", 1.0, "wait", 0.6, "depot", 0.4, "deviation", 0.2));

	public final Map<String, Double> presetWeights;

	Strategy(Map<String, Double> presetWeights) {
		this.presetWeights = presetWeights;
	}

	public static Strategy parse(String value) {
		if (value == null || value.isBlank()) {
			return BALANCED;
		}
		for (final Strategy strategy : values()) {
			if (strategy.name().equalsIgnoreCase(value.trim())) {
				return strategy;
			}
		}
		return BALANCED;
	}

	public Map<String, Object> describe() {
		final Map<String, Object> result = new LinkedHashMap<>();
		result.put("name", name().toLowerCase(java.util.Locale.ROOT));
		result.put("presetWeights", new LinkedHashMap<>(presetWeights));
		return result;
	}
}
