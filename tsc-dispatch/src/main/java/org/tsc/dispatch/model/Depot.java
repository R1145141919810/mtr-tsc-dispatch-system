package org.tsc.dispatch.model;

import java.util.List;

/**
 * A depot, identified by name in the stock API (route payloads list depot names only).
 * {@code id} stays null until a bridge or a future API exposes it.
 */
public record Depot(String id, String name, List<String> routeHexes, List<String> sidingIds) {

	public Depot {
		routeHexes = routeHexes == null ? List.of() : List.copyOf(routeHexes);
		sidingIds = sidingIds == null ? List.of() : List.copyOf(sidingIds);
	}

	public String key() {
		return id == null || id.isBlank() ? "depot:" + name : "depot:" + id;
	}
}
