package org.tsc.dispatch.model;

import java.util.List;

/**
 * A station as published by {@code /mtr/api/map/stations-and-routes}. Platform identity is
 * intentionally absent: the stock endpoint exposes route stops keyed by station id, and the
 * platform level detail only appears in arrivals responses.
 */
public record Station(String hex, String name, int color, List<String> connections) {

	public Station {
		connections = connections == null ? List.of() : List.copyOf(connections);
	}
}
