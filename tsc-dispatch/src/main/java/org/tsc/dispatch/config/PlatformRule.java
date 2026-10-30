package org.tsc.dispatch.config;

import java.util.List;

/**
 * One platform allow/deny rule.
 *
 * <p>{@code route} may be a route number ({@code "A"}), a route hex id, or {@code "*"}.
 * {@code station} is a station hex id or {@code "*"}. When {@code deny} is false the platform
 * list is an allow-list; when true it is a deny-list.</p>
 */
public record PlatformRule(String route, String station, List<String> platforms, boolean deny) {

	public PlatformRule {
		platforms = platforms == null ? List.of() : List.copyOf(platforms);
	}

	public boolean matches(String routeHex, String routeNumber, String stationHex) {
		return matchesRoute(routeHex, routeNumber) && matchesStation(stationHex);
	}

	private boolean matchesRoute(String routeHex, String routeNumber) {
		return "*".equals(route) || route.equalsIgnoreCase(routeHex) || route.equalsIgnoreCase(routeNumber);
	}

	private boolean matchesStation(String stationHex) {
		return "*".equals(station) || station.equalsIgnoreCase(stationHex);
	}

	public boolean contains(String platformHex) {
		for (final String platform : platforms) {
			if (platform.equalsIgnoreCase(platformHex) || "*".equals(platform)) {
				return true;
			}
		}
		return false;
	}
}
