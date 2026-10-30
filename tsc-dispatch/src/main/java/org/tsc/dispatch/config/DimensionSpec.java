package org.tsc.dispatch.config;

/**
 * One dimension the service schedules. {@code index} is the value sent as {@code ?dimension=}
 * and is bound to the order the host server loaded its worlds, so it must be configured, never
 * guessed from the id.
 */
public record DimensionSpec(String id, int index, boolean enabled) {

	public String key() {
		return id + "#" + index;
	}

	public String stateDirectoryName() {
		return "dim-" + index;
	}
}
