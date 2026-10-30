package org.tsc.dispatch.plan;

import org.tsc.dispatch.graph.Edge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result of one planning query: the node sequence, the edges taken and its predicted cost. */
public record PlanResult(String fromNode, String targetNode, List<String> nodes, List<Edge> edges,
						 long departMillis, long arrivalMillis, double cost, boolean reachedTarget) {

	public PlanResult {
		nodes = nodes == null ? List.of() : List.copyOf(nodes);
		edges = edges == null ? List.of() : List.copyOf(edges);
	}

	public static PlanResult unreachable(String fromNode, String targetNode, long departMillis) {
		return new PlanResult(fromNode, targetNode, List.of(), List.of(), departMillis, departMillis, Double.POSITIVE_INFINITY, false);
	}

	public long travelMillis() {
		return Math.max(0L, arrivalMillis - departMillis);
	}

	public Map<String, Object> toJson() {
		final Map<String, Object> result = new LinkedHashMap<>();
		result.put("from", fromNode);
		result.put("target", targetNode);
		result.put("reached", reachedTarget);
		result.put("cost", Math.round(cost * 100.0) / 100.0);
		result.put("depart", departMillis);
		result.put("arrival", arrivalMillis);
		result.put("travelSeconds", Math.round(travelMillis() / 100.0) / 10.0);
		result.put("nodes", nodes);
		final List<Object> edgeList = new ArrayList<>();
		for (final Edge edge : edges) {
			final Map<String, Object> item = new LinkedHashMap<>();
			item.put("from", edge.from());
			item.put("to", edge.to());
			item.put("route", edge.routeHex());
			item.put("seconds", Math.round(edge.travelSeconds() * 10.0) / 10.0);
			edgeList.add(item);
		}
		result.put("edges", edgeList);
		return result;
	}
}
