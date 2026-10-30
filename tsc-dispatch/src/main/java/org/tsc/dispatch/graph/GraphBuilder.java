package org.tsc.dispatch.graph;

import org.tsc.dispatch.config.Config;
import org.tsc.dispatch.model.Route;
import org.tsc.dispatch.model.WorldSnapshot;
import org.tsc.dispatch.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the published route topology into a planner graph.
 *
 * <p>Segment run times come from {@code route.durations} when the simulator published a
 * usable array; otherwise they are estimated from the stop coordinates at a configurable
 * cruise speed plus the destination dwell. Depot nodes are attached to every station of a
 * route that lists that depot, using a configurable access time, because the stock API does
 * not publish depot geometry.</p>
 */
public final class GraphBuilder {

	private GraphBuilder() {
	}

	public static RailGraph build(WorldSnapshot snapshot, Config config) {
		final Map<String, Map<String, Edge>> edgeIndex = new LinkedHashMap<>();
		final Map<String, String> labels = new LinkedHashMap<>();
		final double speed = Math.max(1.0, config.getDouble("planner.estimate-speed-mps"));
		final long depotAccessMillis = (long) (config.getDouble("planner.depot-access-seconds") * 1000);

		for (final Route route : snapshot.routes.values()) {
			final List<Route.Stop> stops = route.stops();
			if (stops.size() < 2) {
				continue;
			}
			final int segmentCount = route.circular() ? stops.size() : stops.size() - 1;
			final boolean durationsUsable = route.durations().size() >= segmentCount;
			for (int index = 0; index < segmentCount; index++) {
				final Route.Stop from = stops.get(index);
				final Route.Stop to = stops.get((index + 1) % stops.size());
				final String fromNode = RailGraph.stationNode(from.stationHex());
				final String toNode = RailGraph.stationNode(to.stationHex());
				if (fromNode.equals(toNode)) {
					continue;
				}
				labels.putIfAbsent(fromNode, from.name().isEmpty() ? from.stationHex() : from.name());
				labels.putIfAbsent(toNode, to.name().isEmpty() ? to.stationHex() : to.name());

				final long travelMillis;
				if (durationsUsable && route.durations().get(index) > 0) {
					travelMillis = route.durations().get(index) + to.dwellMillis();
				} else {
					final double meters = haversine(from, to);
					travelMillis = (long) (meters / speed * 1000.0) + to.dwellMillis();
				}
				final double meters = haversine(from, to);
				put(edgeIndex, new Edge(fromNode, toNode, route.hex(), travelMillis, meters));
			}

			// Some routes publish one duration more than they have segments: that extra entry is the
			// turnback / depot leg. When present, use it as the depot access time and attach the
			// depot to the terminals only - a depot is not reachable from a mid-line platform, and
			// pretending otherwise makes every depot return look cheaper than it is.
			final boolean hasTurnbackLeg = !route.circular() && route.durations().size() == stops.size() && stops.size() > 1;
			final long routeDepotAccess = hasTurnbackLeg
				? Math.max(1_000L, route.durations().get(stops.size() - 1))
				: depotAccessMillis;
			final List<Route.Stop> depotStations = hasTurnbackLeg
				? List.of(stops.get(0), stops.get(stops.size() - 1))
				: stops;
			for (final String depotName : route.depots()) {
				final String depotNode = RailGraph.depotNode(depotName);
				labels.putIfAbsent(depotNode, "depot:" + depotName);
				for (final Route.Stop stop : depotStations) {
					final String stationNode = RailGraph.stationNode(stop.stationHex());
					labels.putIfAbsent(stationNode, stop.name().isEmpty() ? stop.stationHex() : stop.name());
					put(edgeIndex, new Edge(stationNode, depotNode, route.hex(), routeDepotAccess, 0));
					put(edgeIndex, new Edge(depotNode, stationNode, route.hex(), routeDepotAccess, 0));
				}
			}
		}

		final Map<String, List<Edge>> adjacency = new LinkedHashMap<>();
		edgeIndex.forEach((from, targets) -> adjacency.put(from, new ArrayList<>(targets.values())));
		labels.keySet().forEach(node -> adjacency.putIfAbsent(node, List.of()));
		Log.debug("rail graph built", "nodes", labels.size(), "edges", adjacency.values().stream().mapToInt(List::size).sum());
		return new RailGraph(adjacency, labels);
	}

	private static void put(Map<String, Map<String, Edge>> index, Edge edge) {
		final Map<String, Edge> targets = index.computeIfAbsent(edge.from(), key -> new LinkedHashMap<>());
		final Edge existing = targets.get(edge.to());
		if (existing == null || edge.travelMillis() < existing.travelMillis()) {
			targets.put(edge.to(), edge);
		}
	}

	static double haversine(Route.Stop from, Route.Stop to) {
		final double dx = to.x() - from.x();
		final double dy = to.y() - from.y();
		final double dz = to.z() - from.z();
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
