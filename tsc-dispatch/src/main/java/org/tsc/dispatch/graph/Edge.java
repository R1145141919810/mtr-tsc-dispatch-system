package org.tsc.dispatch.graph;

/**
 * A directed rail edge between two graph nodes. {@code travelMillis} already includes the
 * dwell time at the destination stop so the planner can compare routes on a like for like
 * basis.
 */
public record Edge(String from, String to, String routeHex, long travelMillis, double meters) {

	public double travelSeconds() {
		return travelMillis / 1000.0;
	}
}
