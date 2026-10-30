package org.tsc.dispatch.model;

import java.util.List;

/**
 * A route with its ordered stops. {@code durations} are the per-segment scheduled run times
 * published by TSC; when the array length does not line up with the stop count the planner
 * falls back to a distance based estimate instead of guessing.
 */
public record Route(String hex, String name, String number, int color, String type,
					String circularState, List<Stop> stops, List<Long> durations, List<String> depots) {

	public Route {
		stops = stops == null ? List.of() : List.copyOf(stops);
		durations = durations == null ? List.of() : List.copyOf(durations);
		depots = depots == null ? List.of() : List.copyOf(depots);
	}

	public boolean circular() {
		return circularState != null && !circularState.isEmpty() && !"NONE".equalsIgnoreCase(circularState);
	}

	public int stopIndex(String stationHex) {
		for (int i = 0; i < stops.size(); i++) {
			if (stops.get(i).stationHex().equals(stationHex)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * A route stop. {@code stationHex} is what the station level graph keys on; the
	 * coordinates are kept for distance based travel time estimation and debugging.
	 */
	public record Stop(String stationHex, String name, int x, int y, int z, long dwellMillis) {
	}
}
