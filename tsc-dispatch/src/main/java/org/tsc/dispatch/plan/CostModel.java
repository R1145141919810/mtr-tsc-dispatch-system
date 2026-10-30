package org.tsc.dispatch.plan;

import org.tsc.dispatch.config.Config;
import org.tsc.dispatch.graph.Edge;
import org.tsc.dispatch.graph.RailGraph;
import org.tsc.dispatch.model.Route;
import org.tsc.dispatch.model.WorldSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cost function for the planner.
 *
 * <p>Every term is expressed in <em>seconds of equivalent delay</em> so the weights are
 * dimensionless and the numbers in the configuration file mean something. The active strategy
 * supplies the defaults; an explicit weight in {@code dispatch.weights} overrides it.</p>
 *
 * <p>Route priority divides the effective travel time of that route: a priority of 2.0 makes
 * the route twice as attractive, which is how "line A outranks line S" enters the maths
 * without a special case in the search.</p>
 */
public final class CostModel {

	private final Config config;
	private final Map<String, Double> priorityByRoute = new LinkedHashMap<>();

	public CostModel(Config config) {
		this(config, null);
	}

	public CostModel(Config config, WorldSnapshot snapshot) {
		this.config = config;
		if (snapshot != null) {
			for (final Route route : snapshot.routes.values()) {
				priorityByRoute.put(route.hex(), config.routePriority(route.hex(), route.number()));
			}
		}
	}

	public double weight(String name) {
		return config.weight(name);
	}

	public double blockedPenaltySeconds() {
		return config.getDouble("dispatch.constraints.blocked_penalty_seconds");
	}

	public double priority(String routeHex) {
		if (routeHex == null) {
			return 1.0;
		}
		final Double known = priorityByRoute.get(routeHex);
		return known == null ? 1.0 : Math.max(0.2, Math.min(5.0, known));
	}

	/** Incremental cost of traversing {@code edge} and arriving at {@code arrivalMillis}. */
	public double edgeCost(Edge edge, long arrivalMillis, Router.PlanContext context) {
		double seconds = (edge.travelSeconds() / priority(edge.routeHex())) * weight("travel");
		if (RailGraph.isDepot(edge.to())) {
			seconds = seconds * Math.max(0.0, weight("depot"));
		}

		final String stationHex = RailGraph.isDepot(edge.to()) ? "" : RailGraph.stationHex(edge.to());
		if (!stationHex.isEmpty() && context != null) {
			final long freeAt = context.freeAt(stationHex, arrivalMillis);
			final long waitMillis = Math.max(0L, freeAt - arrivalMillis);
			if (waitMillis > 0) {
				seconds += (waitMillis / 1000.0) * weight("wait");
			}
			if (!context.isFree(stationHex, arrivalMillis)) {
				seconds += Math.min(blockedPenaltySeconds(), weight("occupancy") * 60.0 + waitMillis / 1000.0);
			}
		}

		if (context != null && context.avoidNodes.contains(edge.to())) {
			seconds += blockedPenaltySeconds();
		}
		return seconds;
	}

	/** Constant term that penalises continuing a service run while already late. */
	public double deviationCost(double deviationSeconds) {
		return Math.max(0.0, deviationSeconds) * weight("deviation");
	}
}
