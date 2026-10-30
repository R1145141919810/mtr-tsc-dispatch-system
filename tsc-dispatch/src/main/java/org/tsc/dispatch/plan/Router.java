package org.tsc.dispatch.plan;

import org.tsc.dispatch.graph.Edge;
import org.tsc.dispatch.graph.RailGraph;
import org.tsc.dispatch.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Time dependent shortest path search (Dijkstra) with A*-style early exit on the target.
 *
 * <p>Edge costs are evaluated at the arrival instant, so congestion that clears while the
 * vehicle is still en route is accounted for. Costs are always positive, which keeps the
 * search monotone even though the weights change over time.</p>
 */
public final class Router {

	private static final int MAX_EXPANSIONS = 50_000;

	private final RailGraph graph;
	private final CostModel costModel;

	public Router(RailGraph graph, CostModel costModel) {
		this.graph = graph;
		this.costModel = costModel;
	}

	public RailGraph graph() {
		return graph;
	}

	public CostModel costModel() {
		return costModel;
	}

	/** Immutable per-query context: who is asking, what is already taken and what to avoid. */
	public static final class PlanContext {
		public final String selfVehicleId;
		public final Occupancy occupancy;
		public final Map<String, Integer> platformCounts;
		public final Set<String> avoidNodes;
		public final double deviationSeconds;

		public PlanContext(String selfVehicleId, Occupancy occupancy, Map<String, Integer> platformCounts,
						   Set<String> avoidNodes, double deviationSeconds) {
			this.selfVehicleId = selfVehicleId == null ? "" : selfVehicleId;
			this.occupancy = occupancy == null ? new Occupancy() : occupancy;
			this.platformCounts = platformCounts == null ? Map.of() : platformCounts;
			this.avoidNodes = avoidNodes == null ? Set.of() : avoidNodes;
			this.deviationSeconds = deviationSeconds;
		}

		public int capacity(String stationHex) {
			return Math.max(1, platformCounts.getOrDefault(stationHex, 1));
		}

		public boolean isFree(String stationHex, long atMillis) {
			final List<Occupancy.Slot> slots = occupancy.slots("station:" + stationHex);
			int overlapping = 0;
			for (final Occupancy.Slot slot : slots) {
				if (selfVehicleId.equals(slot.vehicleId())) {
					continue;
				}
				if (slot.busyFrom() <= atMillis && atMillis < slot.busyUntil()) {
					overlapping++;
				}
			}
			return overlapping < capacity(stationHex);
		}

		/** Earliest instant at or after {@code fromMillis} with spare capacity at the station. */
		public long freeAt(String stationHex, long fromMillis) {
			final int capacity = capacity(stationHex);
			final List<Occupancy.Slot> slots = new ArrayList<>();
			for (final Occupancy.Slot slot : occupancy.slots("station:" + stationHex)) {
				if (!selfVehicleId.equals(slot.vehicleId()) && slot.busyUntil() > fromMillis) {
					slots.add(slot);
				}
			}
			if (slots.isEmpty()) {
				return fromMillis;
			}
			final List<Long> candidates = new ArrayList<>();
			candidates.add(fromMillis);
			for (final Occupancy.Slot slot : slots) {
				if (slot.busyFrom() > fromMillis) {
					candidates.add(slot.busyFrom());
				}
			}
			Collections.sort(candidates);
			long latest = fromMillis;
			for (final long candidate : candidates) {
				int overlapping = 0;
				for (final Occupancy.Slot slot : slots) {
					if (slot.busyFrom() <= candidate && candidate < slot.busyUntil()) {
						overlapping++;
					}
					latest = Math.max(latest, slot.busyUntil());
				}
				if (overlapping < capacity) {
					return candidate;
				}
			}
			return latest;
		}
	}

	private record Label(String node, double cost, long arrivalMillis) {
	}

	public PlanResult plan(String fromNode, String targetNode, long departMillis, PlanContext context) {
		if (fromNode == null || targetNode == null || fromNode.equals(targetNode)) {
			return new PlanResult(fromNode, targetNode, List.of(), List.of(), departMillis, departMillis, 0, true);
		}
		if (!graph.nodes().contains(fromNode) || !graph.nodes().contains(targetNode)) {
			return PlanResult.unreachable(fromNode, targetNode, departMillis);
		}

		final Map<String, Double> bestCost = new HashMap<>();
		final Map<String, Long> bestArrival = new HashMap<>();
		final Map<String, Edge> parentEdge = new HashMap<>();
		final PriorityQueue<Label> open = new PriorityQueue<>(Comparator.comparingDouble(Label::cost));

		bestCost.put(fromNode, 0.0);
		bestArrival.put(fromNode, departMillis);
		open.add(new Label(fromNode, 0.0, departMillis));

		int expansions = 0;
		while (!open.isEmpty() && expansions++ < MAX_EXPANSIONS) {
			final Label current = open.poll();
			final Double known = bestCost.get(current.node());
			if (known != null && current.cost() > known + 1e-9) {
				continue;
			}
			if (current.node().equals(targetNode)) {
				return reconstruct(fromNode, targetNode, departMillis, current, parentEdge);
			}
			for (final Edge edge : graph.outgoing(current.node())) {
				final long arrival = current.arrivalMillis() + edge.travelMillis();
				final double increment = costModel.edgeCost(edge, current.arrivalMillis(), context);
				final double nextCost = current.cost() + increment;
				final Double existing = bestCost.get(edge.to());
				if (existing == null || nextCost + 1e-9 < existing) {
					bestCost.put(edge.to(), nextCost);
					bestArrival.put(edge.to(), arrival);
					parentEdge.put(edge.to(), edge);
					open.add(new Label(edge.to(), nextCost, arrival));
				}
			}
		}

		Log.debug("planning exhausted without target", "from", fromNode, "target", targetNode);
		return PlanResult.unreachable(fromNode, targetNode, departMillis);
	}

	/** Best plan to any depot reachable from {@code fromNode}. */
	public PlanResult planToNearestDepot(String fromNode, long departMillis, PlanContext context) {
		PlanResult best = null;
		for (final String node : graph.nodes()) {
			if (!RailGraph.isDepot(node)) {
				continue;
			}
			final PlanResult candidate = plan(fromNode, node, departMillis, context);
			if (candidate.reachedTarget() && (best == null || candidate.cost() < best.cost())) {
				best = candidate;
			}
		}
		return best == null ? PlanResult.unreachable(fromNode, "", departMillis) : best;
	}

	private PlanResult reconstruct(String fromNode, String targetNode, long departMillis, Label last, Map<String, Edge> parentEdge) {
		final List<String> nodes = new ArrayList<>();
		final List<Edge> edges = new ArrayList<>();
		String cursor = targetNode;
		nodes.add(cursor);
		int guard = 0;
		while (!cursor.equals(fromNode) && guard++ < 10_000) {
			final Edge edge = parentEdge.get(cursor);
			if (edge == null) {
				break;
			}
			edges.add(edge);
			cursor = edge.from();
			nodes.add(cursor);
		}
		Collections.reverse(nodes);
		Collections.reverse(edges);
		return new PlanResult(fromNode, targetNode, nodes, edges, departMillis, last.arrivalMillis(), last.cost(), true);
	}

	/** Convenience helper for the admin API and tests. */
	public Map<String, Object> planJson(String fromNode, String targetNode, long departMillis, PlanContext context) {
		final Map<String, Object> result = new LinkedHashMap<>();
		final PlanResult plan = plan(fromNode, targetNode, departMillis, context);
		result.put("plan", plan.toJson());
		result.put("labels", labelMap(plan.nodes()));
		return result;
	}

	public Map<String, String> labelMap(List<String> nodes) {
		final Map<String, String> labels = new LinkedHashMap<>();
		for (final String node : nodes) {
			labels.put(node, graph.label(node));
		}
		return labels;
	}

	public Set<String> stationNodes() {
		final Set<String> nodes = new HashSet<>();
		for (final String node : graph.nodes()) {
			if (!RailGraph.isDepot(node)) {
				nodes.add(node);
			}
		}
		return nodes;
	}
}
