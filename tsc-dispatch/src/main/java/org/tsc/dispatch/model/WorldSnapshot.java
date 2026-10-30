package org.tsc.dispatch.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable world view produced by one telemetry poll. Everything downstream (graph, planner,
 * dispatcher) reads this and never touches a {@code TelemetrySource} directly, which keeps the
 * decision pipeline deterministic and trivially testable with hand built snapshots.
 */
public final class WorldSnapshot {

	public final long fetchedAtMillis;
	public final long serverTimeMillis;
	public final String source;
	public final boolean vehicleTelemetry;
	public final Map<String, Station> stations;
	public final Map<String, Route> routes;
	public final Map<String, Platform> platforms;
	public final Map<String, Depot> depots;
	public final List<Vehicle> vehicles;

	private final Map<String, List<Vehicle>> vehiclesByStation = new LinkedHashMap<>();
	private final Map<String, List<Vehicle>> vehiclesByPlatform = new LinkedHashMap<>();
	private final Map<String, List<Platform>> platformsByStation = new LinkedHashMap<>();

	public WorldSnapshot(long fetchedAtMillis, long serverTimeMillis, String source, boolean vehicleTelemetry,
						 Collection<Station> stations, Collection<Route> routes, Collection<Platform> platforms,
						 Collection<Depot> depots, Collection<Vehicle> vehicles) {
		this.fetchedAtMillis = fetchedAtMillis;
		this.serverTimeMillis = serverTimeMillis;
		this.source = source;
		this.vehicleTelemetry = vehicleTelemetry;
		this.stations = index(stations, Station::hex);
		this.routes = index(routes, Route::hex);
		this.platforms = index(platforms, Platform::hex);
		this.depots = new LinkedHashMap<>();
		if (depots != null) {
			depots.forEach(depot -> this.depots.put(depot.key(), depot));
		}
		this.vehicles = vehicles == null ? List.of() : List.copyOf(vehicles);

		for (final Platform platform : this.platforms.values()) {
			platformsByStation.computeIfAbsent(platform.stationHex(), key -> new ArrayList<>()).add(platform);
		}
		for (final Vehicle vehicle : this.vehicles) {
			if (!vehicle.stationHex.isEmpty()) {
				vehiclesByStation.computeIfAbsent(vehicle.stationHex, key -> new ArrayList<>()).add(vehicle);
			}
			final String platformKey = vehicle.platformHex.isEmpty() ? "station:" + vehicle.stationHex : vehicle.platformHex;
			vehiclesByPlatform.computeIfAbsent(platformKey, key -> new ArrayList<>()).add(vehicle);
		}
	}

	private static <T> Map<String, T> index(Collection<T> values, java.util.function.Function<T, String> key) {
		final Map<String, T> result = new LinkedHashMap<>();
		if (values != null) {
			values.forEach(value -> result.put(key.apply(value), value));
		}
		return result;
	}

	public List<Platform> platformsOf(String stationHex) {
		return platformsByStation.getOrDefault(stationHex, List.of());
	}

	public List<Vehicle> vehiclesAt(String platformHex) {
		return vehiclesByPlatform.getOrDefault(platformHex, List.of());
	}

	public List<Vehicle> vehiclesForStation(String stationHex) {
		return vehiclesByStation.getOrDefault(stationHex, List.of());
	}

	public Vehicle vehicle(String id) {
		for (final Vehicle vehicle : vehicles) {
			if (vehicle.id.equals(id)) {
				return vehicle;
			}
		}
		return null;
	}

	public Route routeOf(Vehicle vehicle) {
		return routes.get(vehicle.routeHex);
	}

	public Station stationOf(String platformHex) {
		final Platform platform = platforms.get(platformHex);
		return platform == null ? null : stations.get(platform.stationHex());
	}

	public Depot depot(String name) {
		if (name == null) {
			return null;
		}
		for (final Depot depot : depots.values()) {
			if (name.equals(depot.name())) {
				return depot;
			}
		}
		return null;
	}

	public Map<String, Object> summary() {
		final Map<String, Object> result = new LinkedHashMap<>();
		result.put("source", source);
		result.put("vehicleTelemetry", vehicleTelemetry);
		result.put("fetchedAt", fetchedAtMillis);
		result.put("serverTime", serverTimeMillis);
		result.put("stations", stations.size());
		result.put("platforms", platforms.size());
		result.put("routes", routes.size());
		result.put("depots", depots.size());
		result.put("vehicles", vehicles.size());
		result.put("controllableVehicles", vehicles.stream().filter(vehicle -> vehicle.controllable).count());
		return result;
	}
}
