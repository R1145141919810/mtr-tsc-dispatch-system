package org.tsc.dispatch.dispatch;

import org.tsc.dispatch.config.Config;
import org.tsc.dispatch.graph.GraphBuilder;
import org.tsc.dispatch.graph.RailGraph;
import org.tsc.dispatch.model.Route;
import org.tsc.dispatch.model.Vehicle;
import org.tsc.dispatch.model.WorldSnapshot;
import org.tsc.dispatch.plan.CostModel;
import org.tsc.dispatch.plan.Occupancy;
import org.tsc.dispatch.plan.PlanResult;
import org.tsc.dispatch.plan.Router;
import org.tsc.dispatch.util.Log;
import org.tsc.dispatch.util.Metrics;

import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decision engine.
 *
 * <p>One pass over the snapshot evaluates four independent trigger families and produces
 * zero or more {@link Decision}s. Evaluation never blocks on I/O so it can be unit tested
 * against hand built snapshots; execution is delegated to {@link CommandBus}.</p>
 *
 * <ol>
 *   <li><b>Platform selection</b> &mdash; a vehicle about to call at a station whose platform
 *       is already claimed by a nearby train is moved to the platform that frees up first.</li>
 *   <li><b>Junction reroute</b> &mdash; an approaching vehicle whose next station has no
 *       spare capacity gets an alternative path computed around that station. The decision is
 *       only emitted when the alternative is meaningfully cheaper than staying in line.</li>
 *   <li><b>Scheduled depot return</b> &mdash; vehicles whose service time exceeds the
 *       configured budget are sent back to the nearest depot, deferring while passengers are
 *       aboard and the vehicle is not at a terminus.</li>
 *   <li><b>Depot route refresh</b> &mdash; when several vehicles of one depot are badly late,
 *       ask the simulator to regenerate that depot's paths (the only stock-compatible write).</li>
 * </ol>
 */
public final class Dispatcher {

	private final Config config;
	private final Metrics metrics;
	private final LeaseTable leases;
	private final CommandBus bus;
	private final int leadSeconds;
	private final long headwayMillis;
	private final long cooldownMillis;
	private final long commandTtlMillis;
	private final int recentLimit;

	private final Map<String, Long> firstSeen = new ConcurrentHashMap<>();
	private final Map<String, Long> lastDecisionAt = new ConcurrentHashMap<>();
	private final ArrayDeque<Decision> recent = new ArrayDeque<>();
	/** Cumulative count; the ring buffer above only keeps the last {@link #recentLimit} entries. */
	private final java.util.concurrent.atomic.AtomicLong totalDecisions = new java.util.concurrent.atomic.AtomicLong();

	public Dispatcher(Config config, Metrics metrics, LeaseTable leases, CommandBus bus) {
		this.config = config;
		this.metrics = metrics;
		this.leases = leases;
		this.bus = bus;
		this.leadSeconds = Math.max(5, config.getInt("trigger.approach-lead-seconds"));
		this.headwayMillis = config.getLong("trigger.platform-headway-seconds") * 1000L;
		this.cooldownMillis = config.getLong("trigger.cooldown-seconds") * 1000L;
		this.commandTtlMillis = config.getLong("dispatch.command-ttl-seconds") * 1000L;
		this.recentLimit = 200;
	}

	// ------------------------------------------------------------------ public API

	public List<Decision> tick(WorldSnapshot snapshot) {
		final List<Decision> decisions = evaluate(snapshot);
		for (final Decision decision : decisions) {
			if (decision.command == null) {
				record(decision);
				continue;
			}
			if (!decision.executable) {
				decision.status = Decision.Status.ADVISORY;
				decision.outcome = "vehicle not addressable via " + snapshot.source;
				metrics.inc("dispatch_advisories_total{reason=not_addressable}");
				record(decision);
				continue;
			}
			bus.submit(decision, snapshot);
			record(decision);
		}
		return decisions;
	}

	public List<Decision> evaluate(WorldSnapshot snapshot) {
		final long now = snapshot.serverTimeMillis;
		leases.expire(now);
		track(snapshot, now);

		final Occupancy occupancy = Occupancy.from(snapshot);
		overlayLeases(occupancy, now);
		final RailGraph graph = GraphBuilder.build(snapshot, config);
		final CostModel costModel = new CostModel(config);
		final Router router = new Router(graph, costModel);

		final List<Decision> decisions = new ArrayList<>();
		decisions.addAll(platformDecisions(snapshot, occupancy, now));
		decisions.addAll(rerouteDecisions(snapshot, occupancy, graph, router, costModel, now));
		decisions.addAll(returnDecisions(snapshot, occupancy, router, now));
		decisions.addAll(depotRefreshDecisions(snapshot, now));

		metrics.gauge("dispatch_vehicles_tracked", firstSeen.size());
		return decisions;
	}

	public List<Object> recentJson(int limit) {
		final List<Object> result = new ArrayList<>();
		synchronized (recent) {
			int count = 0;
			for (final Decision decision : recent) {
				if (count++ >= limit) {
					break;
				}
				result.add(decision.toJson());
			}
		}
		return result;
	}

	public long totalDecisions() {
		return totalDecisions.get();
	}

	public Map<String, Object> status() {
		final Map<String, Object> result = new LinkedHashMap<>();
		final Map<String, Integer> byStatus = new LinkedHashMap<>();
		final Map<String, Integer> byType = new LinkedHashMap<>();
		final int recentSize;
		synchronized (recent) {
			for (final Decision decision : recent) {
				byStatus.merge(decision.status.name(), 1, Integer::sum);
				byType.merge(decision.type, 1, Integer::sum);
			}
			recentSize = recent.size();
		}
		// Summing recentByType plateaus once the ring fills (that is the "counter frozen at 200"
		// report); the cumulative total never does.
		result.put("total", totalDecisions.get());
		result.put("recentTotal", recentSize);
		result.put("recentByStatus", byStatus);
		result.put("recentByType", byType);
		result.put("trackedVehicles", firstSeen.size());
		result.put("leases", leases.describe());
		return result;
	}

	// ------------------------------------------------------------------ triggers

	private List<Decision> platformDecisions(WorldSnapshot snapshot, Occupancy occupancy, long now) {
		final Map<String, List<Vehicle>> byStation = new LinkedHashMap<>();
		for (final Vehicle vehicle : snapshot.vehicles) {
			if (vehicle.pathLost) {
				// The siding lost its outbound path: the train cannot be dispatched at all, so it
				// must not occupy a platform slot in the planner either.
				metrics.inc("dispatch_skipped_total{reason=path_lost}");
				continue;
			}
			if (!vehicle.stationHex.isEmpty() && !vehicle.platformHex.isEmpty()) {
				byStation.computeIfAbsent(vehicle.stationHex, key -> new ArrayList<>()).add(vehicle);
			}
		}

		final List<Decision> decisions = new ArrayList<>();
		byStation.forEach((stationHex, vehicles) -> {
			// Running trains first: they are committed to their platform, parked ones are not.
			vehicles.sort(java.util.Comparator
				.comparing((Vehicle candidate) -> candidate.inDepot)
				.thenComparingLong(candidate -> candidate.etaMillis));
			final Set<String> claimed = new LinkedHashSet<>();
			for (final Vehicle vehicle : vehicles) {
				final String desired = vehicle.platformHex;
				final boolean conflict = claimed.contains(desired) || busyWithin(snapshot, occupancy, desired, vehicle, now, headwayMillis);
				String chosen = desired;
				String reason = "";
				if (conflict) {
					final PlatformSelector.Choice choice = PlatformSelector.select(snapshot, occupancy, vehicle, claimed,
						now, headwayMillis, config.platformPolicy());
					reason = choice.reason();
					if (choice.changed()) {
						chosen = choice.platformHex();
					}
				}
				claimed.add(chosen);
				if (chosen.equals(desired) || !cooldown(vehicle.id, "PLATFORM_SELECTION", now, cooldownMillis)) {
					continue;
				}
				if (!config.platformPolicy().allows(vehicle.routeHex, vehicle.routeNumber, vehicle.stationHex, chosen)) {
					metrics.inc("dispatch_skipped_total{reason=platform_not_allowed}");
					Log.info("platform rejected by allow-set", "vehicle", vehicle.id, "route", vehicle.routeHex, "platform", chosen);
					continue;
				}

				// A parked train can be re-platformed: the bridge regenerates its siding path.
				// A running train cannot - the simulator freezes the path for the whole run, so a
				// platform change would only be queued for hours. For those the honest lever is
				// the dwell timer, which the bridge exposes as dwell_control.
				final boolean parked = vehicle.inDepot;
				final boolean canHold = bus.actuator().supports().contains(Command.Kind.HOLD);
				if (!parked && !canHold) {
					final Decision advisory = new Decision(now, "PLATFORM_SELECTION", vehicle.id,
						"recommend " + chosen + " but the train is running and cannot be re-platformed", false, null);
					advisory.detail.put("station", stationHex);
					advisory.detail.put("platformFrom", desired);
					advisory.detail.put("platformTo", chosen);
					decisions.add(advisory);
					continue;
				}

				final Command command = new Command();
				if (parked) {
					command.kind = Command.Kind.SET_PLATFORM;
					command.args.put("stationId", vehicle.stationHex);
					command.args.put("platformId", chosen);
					command.args.put("previousPlatformId", desired);
					command.args.put("eta", vehicle.etaMillis);
					command.idempotencyKey = idempotencyKey("platform", vehicle.id, chosen, vehicle.etaMillis);
				} else {
					final long freeAt = PlatformSelector.freeAt(occupancy, desired,
						Math.max(now, vehicle.etaMillis), vehicle.id, headwayMillis);
					final long seconds = Math.max(10L, Math.min(120L, Math.max(0L, freeAt - vehicle.etaMillis) / 1000L));
					command.kind = Command.Kind.HOLD;
					command.args.put("seconds", seconds);
					command.args.put("platformId", chosen);
					command.idempotencyKey = idempotencyKey("hold", vehicle.id, desired, vehicle.etaMillis);
					reason = "held at the next stop for " + seconds + "s so the platform clears";
				}
				command.vehicleId = vehicle.id;
				command.reason = reason;
				command.preconditionVehicleId = vehicle.id;
				command.preconditionPlatformHex = desired;
				command.preconditionObservedAt = snapshot.fetchedAtMillis;
				command.expiresAtMillis = now + commandTtlMillis;
				command.maxAttempts = Math.max(1, config.getInt("dispatch.max-attempts"));

				final boolean leased = !parked || leases.tryAcquire("platform:" + chosen, vehicle.id, now,
					config.getLong("dispatch.lease-ttl-seconds") * 1000L, vehicle.etaMillis);
				if (!leased) {
					metrics.inc("dispatch_skipped_total{reason=lease_conflict}");
					continue;
				}

				final String decisionType = command.kind == Command.Kind.HOLD ? "HOLD_FOR_PLATFORM" : "PLATFORM_SELECTION";
				final Decision decision = new Decision(now, decisionType, vehicle.id, reason, vehicle.controllable, command);
				decision.detail.put("parked", parked);
				decision.detail.put("station", stationHex);
				decision.detail.put("platformFrom", desired);
				decision.detail.put("platformTo", chosen);
				decision.detail.put("eta", vehicle.etaMillis);
				decision.detail.put("route", vehicle.routeHex);
				decisions.add(decision);
			}
		});
		return decisions;
	}

	private List<Decision> rerouteDecisions(WorldSnapshot snapshot, Occupancy occupancy, RailGraph graph,
											Router router, CostModel costModel, long now) {
		final List<Decision> decisions = new ArrayList<>();
		final Map<String, Integer> platformCounts = platformCounts(snapshot);

		for (final Vehicle vehicle : snapshot.vehicles) {
			if (vehicle.pathLost) {
				metrics.inc("dispatch_skipped_total{reason=path_lost}");
				continue;
			}
			final long secondsToArrival = (vehicle.etaMillis - now) / 1000;
			if (secondsToArrival <= 0 || secondsToArrival > leadSeconds) {
				continue;
			}
			final Route route = snapshot.routes.get(vehicle.routeHex);
			if (route == null || route.stops().size() < 3) {
				continue;
			}
			final List<Route.Stop> stops = route.stops();
			final int index = route.stopIndex(vehicle.stationHex);
			if (index < 0) {
				continue;
			}
			final int nextIndex = (index + 1) % stops.size();
			final String nextStation = stops.get(nextIndex).stationHex();
			final int targetIndex = (nextIndex + 1) % stops.size();
			final String targetStation = stops.get(targetIndex).stationHex();
			if (nextStation.equals(targetStation) || targetStation.equals(vehicle.stationHex)) {
				continue;
			}

			final Router.PlanContext context = new Router.PlanContext(vehicle.id, occupancy, platformCounts, Set.of(),
				vehicle.deviationMillis / 1000.0);
			final long freeAt = context.freeAt(nextStation, vehicle.etaMillis);
			if (freeAt <= vehicle.etaMillis + 15_000L) {
				continue;
			}

			final String fromNode = RailGraph.stationNode(vehicle.stationHex);
			final String nextNode = RailGraph.stationNode(nextStation);
			final String targetNode = RailGraph.stationNode(targetStation);

			// The vehicle is already committed to the next station on its route, so the
			// comparison is "pay the wait there" versus "go around it" - not a free choice of
			// the cheapest path from this station, which a routed packet could make but a
			// train on rails cannot.
			final org.tsc.dispatch.graph.Edge committed = cheapestEdge(graph, fromNode, nextNode);
			if (committed == null) {
				continue;
			}
			final long arrivalAtNext = vehicle.etaMillis + committed.travelMillis();
			final PlanResult onward = router.plan(nextNode, targetNode, arrivalAtNext, context);
			final double stayingCost = onward.reachedTarget()
				? costModel.edgeCost(committed, vehicle.etaMillis, context) + onward.cost()
				: Double.MAX_VALUE;

			final Router.PlanContext avoidContext = new Router.PlanContext(vehicle.id, occupancy, platformCounts,
				Set.of(nextNode), vehicle.deviationMillis / 1000.0);
			final PlanResult alternative = router.plan(fromNode, targetNode, vehicle.etaMillis, avoidContext);
			if (!alternative.reachedTarget() || alternative.cost() + 30.0 >= stayingCost) {
				continue;
			}
			if (!cooldown(vehicle.id, "REROUTE", now, cooldownMillis * 2)) {
				continue;
			}

			final Command command = new Command();
			command.kind = Command.Kind.REROUTE;
			command.vehicleId = vehicle.id;
			command.args.put("targetStationId", targetStation);
			command.args.put("avoidStationId", nextStation);
			command.args.put("via", alternative.nodes().subList(1, Math.min(alternative.nodes().size(), 6)));
			command.args.put("planCost", Math.round(alternative.cost() * 10.0) / 10.0);
			command.reason = "next station congested until +" + Math.max(0, freeAt - vehicle.etaMillis) / 1000 + "s";
			command.preconditionVehicleId = vehicle.id;
			command.preconditionPlatformHex = vehicle.platformHex;
			command.preconditionObservedAt = snapshot.fetchedAtMillis;
			command.expiresAtMillis = now + commandTtlMillis;
			command.maxAttempts = Math.max(1, config.getInt("dispatch.max-attempts"));
			command.idempotencyKey = idempotencyKey("reroute", vehicle.id, nextStation, vehicle.etaMillis);

			leases.tryAcquire("station:" + targetStation, vehicle.id, now,
				config.getLong("dispatch.lease-ttl-seconds") * 1000L, vehicle.etaMillis);

			final Decision decision = new Decision(now, "REROUTE", vehicle.id, command.reason, vehicle.controllable, command);
			decision.detail.put("avoid", nextStation);
			decision.detail.put("target", targetStation);
			decision.detail.put("costStay", Math.round(stayingCost * 10.0) / 10.0);
			decision.detail.put("costAlternative", Math.round(alternative.cost() * 10.0) / 10.0);
			decision.detail.put("plan", alternative.toJson());
			decisions.add(decision);
		}
		return decisions;
	}

	private List<Decision> returnDecisions(WorldSnapshot snapshot, Occupancy occupancy, Router router, long now) {
		final long threshold = config.getLong("trigger.return-after-seconds") * 1000L;
		final boolean inWindow = ReturnWindow.matches(config.get("trigger.return-at-local-time"), LocalTime.now());
		if (threshold <= 0 && !inWindow) {
			return List.of();
		}

		final List<Decision> decisions = new ArrayList<>();
		final Map<String, Integer> platformCounts = platformCounts(snapshot);
		for (final Vehicle vehicle : snapshot.vehicles) {
			if (vehicle.inDepot || vehicle.pathLost) {
				if (vehicle.pathLost) {
					metrics.inc("dispatch_skipped_total{reason=path_lost}");
				}
				continue;
			}
			final long start = vehicle.serviceStartMillis > 0 ? vehicle.serviceStartMillis : firstSeen.getOrDefault(vehicle.id, now);
			final long elapsed = now - start;
			final boolean timeUp = threshold > 0 && elapsed >= threshold;
			if (!timeUp && !inWindow) {
				continue;
			}
			if (!cooldown(vehicle.id, "RETURN_TO_DEPOT", now, 600_000L)) {
				continue;
			}

			final Route route = snapshot.routes.get(vehicle.routeHex);
			final boolean atTerminus = route != null && route.stopIndex(vehicle.stationHex) == route.stops().size() - 1;
			final boolean carryingPassengers = vehicle.passengerCount > 0;
			final String fromNode = RailGraph.stationNode(vehicle.stationHex);

			if (vehicle.atPlatform && !atTerminus && carryingPassengers) {
				final Decision decision = new Decision(now, "RETURN_TO_DEPOT", vehicle.id,
					"deferred: passengers aboard and not at a terminus", false, null);
				decision.detail.put("serviceSeconds", elapsed / 1000);
				decision.detail.put("passengers", vehicle.passengerCount);
				decision.detail.put("station", vehicle.stationHex);
				decisions.add(decision);
				continue;
			}

			final Router.PlanContext context = new Router.PlanContext(vehicle.id, occupancy, platformCounts, Set.of(),
				vehicle.deviationMillis / 1000.0);
			PlanResult plan = null;
			int fullDepots = 0;
			for (final org.tsc.dispatch.model.Depot depot : snapshot.depots.values()) {
				if (!DepotCapacity.hasRoom(config, snapshot, depot.name(), vehicle.id)) {
					fullDepots++;
					metrics.inc("dispatch_skipped_total{reason=depot_full}");
					continue;
				}
				final PlanResult candidate = router.plan(fromNode, RailGraph.depotNode(depot.name()), vehicle.etaMillis, context);
				if (candidate.reachedTarget() && (plan == null || candidate.cost() < plan.cost())) {
					plan = candidate;
				}
			}
			if (plan == null) {
				final Decision decision = new Decision(now, "RETURN_TO_DEPOT", vehicle.id,
					fullDepots > 0 ? "every reachable depot is at capacity" : "no reachable depot from this position", false, null);
				decision.detail.put("serviceSeconds", elapsed / 1000);
				decision.detail.put("fullDepots", fullDepots);
				decisions.add(decision);
				continue;
			}

			final String depotName = RailGraph.depotName(plan.targetNode());
			final Command command = new Command();
			command.kind = Command.Kind.RETURN_TO_DEPOT;
			command.vehicleId = vehicle.id;
			command.args.put("depot", depotName);
			command.args.put("stationId", vehicle.stationHex);
			command.args.put("eta", plan.arrivalMillis());
			command.reason = "service budget exhausted (" + elapsed / 1000 + "s)";
			command.preconditionVehicleId = vehicle.id;
			command.preconditionPlatformHex = vehicle.platformHex;
			command.preconditionObservedAt = snapshot.fetchedAtMillis;
			command.expiresAtMillis = now + commandTtlMillis;
			command.maxAttempts = Math.max(1, config.getInt("dispatch.max-attempts"));
			command.idempotencyKey = idempotencyKey("return", vehicle.id, depotName, vehicle.etaMillis);

			leases.tryAcquire("depot:" + depotName, vehicle.id, now,
				config.getLong("dispatch.lease-ttl-seconds") * 1000L, vehicle.etaMillis);

			final Decision decision = new Decision(now, "RETURN_TO_DEPOT", vehicle.id, command.reason, vehicle.controllable, command);
			decision.detail.put("serviceSeconds", elapsed / 1000);
			decision.detail.put("depot", depotName);
			decision.detail.put("plan", plan.toJson());
			decisions.add(decision);
		}
		return decisions;
	}

	private List<Decision> depotRefreshDecisions(WorldSnapshot snapshot, long now) {
		final Map<String, List<Vehicle>> byDepot = new LinkedHashMap<>();
		for (final Vehicle vehicle : snapshot.vehicles) {
			String depot = vehicle.depotName;
			if (depot == null || depot.isEmpty()) {
				final Route route = snapshot.routes.get(vehicle.routeHex);
				depot = route == null || route.depots().isEmpty() ? "" : route.depots().get(0);
			}
			if (!depot.isEmpty()) {
				byDepot.computeIfAbsent(depot, key -> new ArrayList<>()).add(vehicle);
			}
		}

		final List<Decision> decisions = new ArrayList<>();
		final long lateThreshold = 60_000L;
		byDepot.forEach((depot, vehicles) -> {
			final long late = vehicles.stream().filter(vehicle -> vehicle.deviationMillis > lateThreshold).count();
			if (late < 2 || !cooldown(depot, "DEPOT_REFRESH", now, 300_000L)) {
				return;
			}
			final Command command = new Command();
			command.kind = Command.Kind.REFRESH_DEPOT_ROUTES;
			command.vehicleId = "";
			command.args.put("depot", depot);
			command.reason = late + " late vehicles on routes served by this depot";
			command.preconditionVehicleId = "";
			command.preconditionObservedAt = snapshot.fetchedAtMillis;
			command.expiresAtMillis = now + commandTtlMillis;
			command.maxAttempts = Math.max(1, config.getInt("dispatch.max-attempts"));
			command.idempotencyKey = idempotencyKey("refresh", depot, String.valueOf(now / 300_000L), now);
			final Decision decision = new Decision(now, "DEPOT_REFRESH", "", command.reason, true, command);
			decision.detail.put("depot", depot);
			decision.detail.put("lateVehicles", late);
			decisions.add(decision);
		});
		return decisions;
	}

	// ------------------------------------------------------------------ helpers

	private void track(WorldSnapshot snapshot, long now) {
		final Set<String> alive = new LinkedHashSet<>();
		for (final Vehicle vehicle : snapshot.vehicles) {
			alive.add(vehicle.id);
			firstSeen.putIfAbsent(vehicle.id, now);
		}
		firstSeen.keySet().removeIf(id -> !alive.contains(id));
	}

	private void overlayLeases(Occupancy occupancy, long now) {
		for (final LeaseTable.Lease lease : leases.snapshot()) {
			occupancy.withLease(lease.resource(), lease.vehicleId(), now, Math.max(now + 1, lease.expiresAtMillis()));
		}
	}

	/**
	 * A platform is contested when another vehicle is already committed to it <em>earlier</em>
	 * than this one, or when the slot is one of our own leases. Later vehicles yield; the
	 * earliest arrival keeps its platform, which keeps the outcome deterministic.
	 */
	private boolean busyWithin(WorldSnapshot snapshot, Occupancy occupancy, String platformHex, Vehicle vehicle, long now, long headway) {
		final double myPriority = priorityOf(snapshot, vehicle);
		final long windowFrom = Math.max(now, vehicle.etaMillis - 5_000L);
		final long windowTo = vehicle.etaMillis + headway;
		for (final Occupancy.Slot slot : occupancy.slots("platform:" + platformHex)) {
			if (slot.vehicleId().equals(vehicle.id)) {
				continue;
			}
			if (!slot.overlaps(windowFrom, windowTo)) {
				continue;
			}
			if (slot.lease()) {
				return true;
			}
			if (slot.busyFrom() < vehicle.etaMillis - 5_000L) {
				final Vehicle other = snapshot.vehicle(slot.vehicleId());
				if (other != null && priorityOf(snapshot, other) < myPriority) {
					continue; // the other train yields to this higher priority route
				}
				return true;
			}
		}
		return false;
	}

	private double priorityOf(WorldSnapshot snapshot, Vehicle vehicle) {
		final Route route = snapshot.routes.get(vehicle.routeHex);
		return config.routePriority(vehicle.routeHex, route == null ? "" : route.number());
	}

	private static org.tsc.dispatch.graph.Edge cheapestEdge(RailGraph graph, String fromNode, String toNode) {
		org.tsc.dispatch.graph.Edge best = null;
		for (final org.tsc.dispatch.graph.Edge edge : graph.outgoing(fromNode)) {
			if (edge.to().equals(toNode) && (best == null || edge.travelMillis() < best.travelMillis())) {
				best = edge;
			}
		}
		return best;
	}

	private Map<String, Integer> platformCounts(WorldSnapshot snapshot) {
		final Map<String, Integer> counts = new HashMap<>();
		snapshot.platforms.values().forEach(platform -> counts.merge(platform.stationHex(), 1, Integer::sum));
		return counts;
	}

	private boolean cooldown(String key, String type, long now, long millis) {
		final String mapKey = type + "|" + key;
		final Long last = lastDecisionAt.get(mapKey);
		if (last != null && now - last < millis) {
			return false;
		}
		lastDecisionAt.put(mapKey, now);
		return true;
	}

	private String idempotencyKey(String type, String vehicleId, String target, long etaMillis) {
		return type + "|" + vehicleId + "|" + target + "|" + (etaMillis / 60_000L);
	}

	private void record(Decision decision) {
		synchronized (recent) {
			recent.addFirst(decision);
			while (recent.size() > recentLimit) {
				recent.removeLast();
			}
		}
		totalDecisions.incrementAndGet();
		metrics.inc("dispatch_decisions_total{type=" + decision.type + "}");
		Log.info("decision", "type", decision.type, "vehicle", decision.vehicleId, "status", decision.status,
			"executable", decision.executable, "reason", decision.reason);
	}

	/** Parses a {@code HH:mm-HH:mm} wall clock window; empty means "no window". */
	static final class ReturnWindow {

		private ReturnWindow() {
		}

		static boolean matches(String window, LocalTime now) {
			if (window == null || window.isBlank()) {
				return false;
			}
			final String[] parts = window.split("-");
			if (parts.length != 2) {
				return false;
			}
			try {
				final LocalTime from = LocalTime.parse(parts[0].trim());
				final LocalTime to = LocalTime.parse(parts[1].trim());
				if (from.equals(to)) {
					return true;
				}
				if (from.isBefore(to)) {
					return !now.isBefore(from) && now.isBefore(to);
				}
				return !now.isBefore(from) || now.isBefore(to);
			} catch (Exception exception) {
				return false;
			}
		}
	}
}
