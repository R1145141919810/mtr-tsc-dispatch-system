package org.tsc.dispatch.source;

import org.tsc.dispatch.config.Config;
import org.tsc.dispatch.model.Depot;
import org.tsc.dispatch.model.Platform;
import org.tsc.dispatch.model.Route;
import org.tsc.dispatch.model.Station;
import org.tsc.dispatch.model.Vehicle;
import org.tsc.dispatch.model.WorldSnapshot;
import org.tsc.dispatch.util.Ids;
import org.tsc.dispatch.util.Json;
import org.tsc.dispatch.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read adapter for the optional control bridge (protocol v1, {@code /dispatch/v1/state}).
 *
 * <p>The bridge is the only way to obtain what the stock HTTP surface withholds: stable
 * vehicle ids, rail progress, speed, signal block occupancy and depot/siding state. It is a
 * separate, versioned contract; this class never links against game classes.</p>
 */
public final class BridgeSource implements TelemetrySource {

	private final String baseUrl;
	private final HttpJson http;
	private final int dimension;
	private volatile Set<String> features = Set.of();
	private volatile int protocol;
	private volatile boolean ready = true;
	private volatile Map<String, Object> health = Map.of();
	private volatile List<String> dimensionIds = List.of();
	private volatile long minPollIntervalMs;

	public BridgeSource(Config config, HttpJson http) {
		this(config, http, config.getInt("tsc.dimension"));
	}

	public BridgeSource(Config config, HttpJson http, int dimension) {
		this.baseUrl = stripTrailingSlash(config.get("bridge.base-url"));
		this.http = http;
		this.dimension = dimension;
	}

	/** Probes {@code /dispatch/v1/capabilities}; returns false when no bridge answers. */
	public boolean probe() {
		try {
			final Map<String, Object> capabilities = Json.asObject(http.get(baseUrl + "/dispatch/v1/capabilities"));
			protocol = (int) Json.number(capabilities, "protocol", 0);
			final Set<String> found = new LinkedHashSet<>();
			for (final Object feature : Json.asArray(capabilities.get("features"))) {
				found.add(String.valueOf(feature));
			}
			features = Set.copyOf(found);
			ready = Json.bool(capabilities, "ready", true);
			final List<String> ids = new ArrayList<>();
			for (final Object element : Json.asArray(capabilities.get("dimensions"))) {
				final Map<String, Object> entry = Json.asObject(element);
				final int index = (int) Json.number(entry, "index", ids.size());
				while (ids.size() <= index) {
					ids.add("");
				}
				ids.set(index, Json.string(entry, "id", ""));
			}
			dimensionIds = List.copyOf(ids);
			minPollIntervalMs = Json.number(capabilities, "minPollIntervalMs", 0);
			Log.info("control bridge detected", "url", baseUrl, "protocol", protocol, "features", found,
				"ready", ready, "dimensions", dimensionIds, "minPollIntervalMs", minPollIntervalMs);
			return protocol >= 1;
		} catch (Exception exception) {
			Log.info("no control bridge", "url", baseUrl, "reason", exception.getMessage());
			return false;
		}
	}

	public int protocol() {
		return protocol;
	}

	/** {@code /dispatch/v1/health}: ready flag plus the bridge's own counters. */
	public void refreshHealth() {
		try {
			health = Json.asObject(http.get(baseUrl + "/dispatch/v1/health"));
			ready = Json.bool(health, "ready", ready);
		} catch (Exception exception) {
			ready = false;
			Log.debug("bridge health probe failed", "error", exception.getMessage());
		}
	}

	/** False while the bridge is up but no world is loaded - commands would be pointless. */
	public boolean ready() {
		return ready;
	}

	public Map<String, Object> health() {
		return health;
	}

	public List<String> dimensionIds() {
		return dimensionIds;
	}

	public long minPollIntervalMs() {
		return minPollIntervalMs;
	}

	public Map<String, Object> describe() {
		final Map<String, Object> result = new LinkedHashMap<>();
		result.put("configured", true);
		result.put("url", baseUrl);
		result.put("protocol", protocol);
		result.put("features", features);
		result.put("dimension", dimension);
		result.put("dimensions", dimensionIds);
		result.put("minPollIntervalMs", minPollIntervalMs);
		result.put("ready", ready);
		result.put("health", health);
		return result;
	}

	@Override
	public String name() {
		return "tsc-control-bridge";
	}

	@Override
	public Set<String> features() {
		return features;
	}

	@Override
	public boolean vehicleTelemetry() {
		return true;
	}

	@Override
	public WorldSnapshot fetch() {
		final Map<String, Object> state = Json.asObject(http.get(baseUrl + "/dispatch/v1/state?dimension=" + dimension));
		final long stamp = System.currentTimeMillis();
		final long serverTime = Json.number(state, "serverTime", stamp);

		final List<Station> stations = new ArrayList<>();
		for (final Object element : Json.asArray(state.get("stations"))) {
			final Map<String, Object> station = Json.asObject(element);
			final String hex = Ids.normalize(Json.string(station, "id", "0"));
			stations.add(new Station(hex, Json.string(station, "name", hex), (int) Json.number(station, "color", 0), List.of()));
		}

		final List<Platform> platforms = new ArrayList<>();
		final Map<String, String> stationOfPlatform = new LinkedHashMap<>();
		final Map<String, Long> dwellOfPlatform = new LinkedHashMap<>();
		for (final Object element : Json.asArray(state.get("platforms"))) {
			final Map<String, Object> platform = Json.asObject(element);
			final String hex = Ids.normalize(Json.string(platform, "id", "0"));
			final String stationHex = Ids.normalize(Json.string(platform, "stationId", "0"));
			final long dwell = Json.number(platform, "dwellMillis", 20_000);
			platforms.add(new Platform(Ids.parse(hex, 0L), hex, Json.string(platform, "name", hex), stationHex, dwell));
			stationOfPlatform.put(hex, stationHex);
			dwellOfPlatform.put(hex, dwell);
		}

		final List<Route> routes = new ArrayList<>();
		for (final Object element : Json.asArray(state.get("routes"))) {
			final Map<String, Object> route = Json.asObject(element);
			final List<Route.Stop> stops = new ArrayList<>();
			for (final Object stopElement : Json.asArray(route.get("stops"))) {
				final Map<String, Object> stop = Json.asObject(stopElement);
				stops.add(new Route.Stop(
					Ids.normalize(Json.string(stop, "stationId", "0")),
					Json.string(stop, "name", ""),
					(int) Json.number(stop, "x", 0),
					(int) Json.number(stop, "y", 0),
					(int) Json.number(stop, "z", 0),
					Json.number(stop, "dwellMillis", 0)));
			}
			final List<Long> durations = new ArrayList<>();
			for (final Object duration : Json.asArray(route.get("durations"))) {
				durations.add(duration instanceof Number number ? number.longValue() : 0L);
			}
			final List<String> depots = new ArrayList<>();
			for (final Object depot : Json.asArray(route.get("depots"))) {
				depots.add(String.valueOf(depot));
			}
			routes.add(new Route(Ids.normalize(Json.string(route, "id", "0")), Json.string(route, "name", ""),
				Json.string(route, "number", ""), (int) Json.number(route, "color", 0), Json.string(route, "type", "TRAIN"),
				Json.string(route, "circularState", "NONE"), stops, durations, depots));
		}

		final List<Depot> depots = new ArrayList<>();
		for (final Object element : Json.asArray(state.get("depots"))) {
			final Map<String, Object> depot = Json.asObject(element);
			final List<String> routeIds = new ArrayList<>();
			for (final Object routeId : Json.asArray(depot.get("routeIds"))) {
				routeIds.add(Ids.normalize(String.valueOf(routeId)));
			}
			final List<String> sidingIds = new ArrayList<>();
			for (final Object sidingId : Json.asArray(depot.get("sidingIds"))) {
				sidingIds.add(String.valueOf(sidingId));
			}
			depots.add(new Depot(Json.string(depot, "id", null), Json.string(depot, "name", ""), routeIds, sidingIds));
		}

		final Map<String, Vehicle> vehicles = new LinkedHashMap<>();
		for (final Object element : Json.asArray(state.get("vehicles"))) {
			final Map<String, Object> source = Json.asObject(element);
			final Vehicle vehicle = new Vehicle();
			vehicle.origin = name();
			vehicle.id = Json.string(source, "id", "");
			vehicle.name = Json.string(source, "name", vehicle.id);
			vehicle.routeHex = Ids.normalize(Json.string(source, "routeId", "0"));
			vehicle.platformHex = Ids.normalize(Json.string(source, "nextPlatformId", "0"));
			// Semantics 2 reports nextStationId explicitly (the station of the next stop), which is
			// the frame contention is computed in. Older bridges only send thisStationId, so fall
			// back to the platform map and finally to the reported station.
			final String nextStationId = Json.string(source, "nextStationId", "");
			vehicle.stationHex = !nextStationId.isBlank() && !"0000000000000000".equals(nextStationId)
				? Ids.normalize(nextStationId)
				: stationOfPlatform.getOrDefault(vehicle.platformHex,
					Ids.normalize(Json.string(source, "stationId", "0")));
			vehicle.destination = Json.string(source, "destination", "");
			vehicle.depotName = Json.string(source, "depot", "");
			vehicle.etaMillis = Json.number(source, "eta", serverTime);
			vehicle.departureMillis = Json.number(source, "departure", vehicle.etaMillis);
			vehicle.deviationMillis = Json.number(source, "deviation", 0);
			vehicle.railProgress = Json.decimal(source, "railProgress", 0);
			vehicle.speedMps = Json.decimal(source, "speed", 0);
			vehicle.dwellElapsedMillis = Json.number(source, "dwellElapsed", 0);
			vehicle.serviceStartMillis = Json.number(source, "serviceStart", 0);
			vehicle.passengerCount = Json.decimal(source, "passengers", 0);
			vehicle.controllable = Json.bool(source, "controllable", true);
			vehicle.inDepot = Json.bool(source, "inDepot", false);
			vehicle.pathLost = Json.bool(source, "pathLost", false);
			// "atPlatform" must mean "at the platform it will stop at next", not "stopped somewhere".
			vehicle.atPlatform = !vehicle.inDepot && vehicle.etaMillis <= serverTime + 1_500L;
			if (vehicle.departureMillis <= vehicle.etaMillis) {
				vehicle.departureMillis = vehicle.etaMillis + dwellOfPlatform.getOrDefault(vehicle.platformHex, 20_000L);
			}
			if (!vehicle.id.isEmpty()) {
				vehicles.put(vehicle.id, vehicle);
			}
		}

		return new WorldSnapshot(stamp, serverTime, name(), true, stations, routes, platforms, depots, vehicles.values());
	}

	private static String stripTrailingSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}
}
