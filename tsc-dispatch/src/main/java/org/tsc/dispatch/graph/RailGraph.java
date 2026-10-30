package org.tsc.dispatch.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable adjacency structure used by the planner. Nodes are namespaced strings so that a
 * station and a depot can never collide: {@code st:<hex>} and {@code dp:<name>}.
 */
public final class RailGraph {

	public static final String STATION_PREFIX = "st:";
	public static final String DEPOT_PREFIX = "dp:";

	private final Map<String, List<Edge>> adjacency;
	private final Map<String, String> labels;
	private final List<Edge> edges;

	public RailGraph(Map<String, List<Edge>> adjacency, Map<String, String> labels) {
		this.adjacency = adjacency;
		this.labels = labels;
		final List<Edge> flat = new ArrayList<>();
		adjacency.values().forEach(flat::addAll);
		this.edges = List.copyOf(flat);
	}

	public static String stationNode(String stationHex) {
		return STATION_PREFIX + stationHex;
	}

	public static String depotNode(String depotName) {
		return DEPOT_PREFIX + depotName;
	}

	public static boolean isDepot(String node) {
		return node != null && node.startsWith(DEPOT_PREFIX);
	}

	public static String stationHex(String node) {
		return node != null && node.startsWith(STATION_PREFIX) ? node.substring(STATION_PREFIX.length()) : node;
	}

	public static String depotName(String node) {
		return node != null && node.startsWith(DEPOT_PREFIX) ? node.substring(DEPOT_PREFIX.length()) : node;
	}

	public List<Edge> outgoing(String node) {
		return adjacency.getOrDefault(node, List.of());
	}

	public Collection<String> nodes() {
		return labels.keySet();
	}

	public List<Edge> edges() {
		return edges;
	}

	public String label(String node) {
		return labels.getOrDefault(node, node);
	}

	public int nodeCount() {
		return labels.size();
	}

	public int edgeCount() {
		return edges.size();
	}

	public Map<String, Object> describe() {
		final Map<String, Object> result = new LinkedHashMap<>();
		result.put("nodes", nodeCount());
		result.put("edges", edgeCount());
		result.put("labels", new LinkedHashMap<>(labels));
		return result;
	}
}
