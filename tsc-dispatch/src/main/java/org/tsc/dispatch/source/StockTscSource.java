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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read adapter for the HTTP surface that ships with MTR 4 / TSC.
 *
 * <p>What this source can see:</p>
 * <ul>
 *   <li>{@code /mtr/api/map/stations-and-routes} &mdash; station list, route stop order,
 *       scheduled segment durations and the depot names attached to each route;</li>
 *   <li>{@code /mtr/api/map/arrivals} &mdash; predicted arrivals per platform, including the
 *       passenger car list (which is the only vehicle identity the stock surface exposes);</li>
 *   <li>{@code /mtr/api/map/departures} &mdash; per-route departures grouped by deviation.</li>
 * </ul>
 *
 * <p>What it cannot see: live rail positions, speeds, signal block occupancy and depot/siding
 * state. Those never leave the simulator through HTTP, so the planner works at station and
 * platform granularity here and every decision degrades to an advisory.</p>
 *
 * <p>Platform to station mapping needs one arrivals query per station, because the batched
 * response identifies platforms only by numeric id. That discovery runs at the topology
 * cadence and is cached.</p>
 */
public final class StockTscSource implements TelemetrySource {

	public static final Set<String> FEATURES = Set.of("stations", "routes", "platforms", "arrivals", "departures");

	private final Config config;
	private final HttpJson http;
	private final String baseUrl;
	private final int dimension;
	private final Map<String, String> platformToStation = new ConcurrentHashMap<>();
	private final Map<String, Platform> platformCache = new ConcurrentHashMap<>();

	private volatile Map<String, Station> stations = Map.of();
	private volatile Map<String, Route> routes = Map.of();
	private volatile Map<String, Depot> depots = Map.of();
	private volatile long topologyFetchedAt;
	private volatile boolean topologyValid;

	public StockTscSource(Config config, HttpJson http) {
		this(config, http, config.getInt("tsc.dimension"), config.get("tsc.base-url"));
	}

	public StockTscSource(Config config, HttpJson http, int dimension, String baseUrl) {
		this.config = config;
		this.http = http;
		this.baseUrl = stripTrailingSlash(baseUrl);
		this.dimension = dimension;
	}

	@Override
	public String name() {
		return "tsc-stock-http";
	}

	@Override
	public Set<String> features() {
		return FEATURES;
	}

	@Override
	public boolean vehicleTelemetry() {
		return false;
	}

	@Override
	public WorldSnapshot fetch() {
		final long now = System.currentTimeMillis();
		final long topologyInterval = config.getLong("tsc.static-poll-interval-ms");
		if (!topologyValid || now - topologyFetchedAt > topologyInterval) {
			refreshTopology(now);
			topologyValid = true;
			topologyFetchedAt = now;
		}

		final Map<String, Vehicle> vehicles = new LinkedHashMap<>();
		final long stamp = System.currentTimeMillis();
		collectArrivals(vehicles, stamp);
		return new WorldSnapshot(stamp, stamp, name(), false, stations.values(), routes.values(),
			platformCache.values(), depots.values(), vehicles.values());
	}

	private void refreshTopology(long now) {
		final Object payload = http.get(baseUrl + "/mtr/api/map/stations-and-routes?dimension=" + dimension);
		final Map<String, Object> data = Json.asObject(payload);

		final Map<String, Station> newStations = new LinkedHashMap<>();
		for (final Object element : Json.asArray(data.get("stations"))) {
			final Map<String, Object> station = Json.asObject(element);
			final String hex = Json.string(station, "id", "");
			if (hex.isEmpty()) {
				continue;
			}
			final List<String> connections = new ArrayList<>();
			for (final Object connection : Json.asArray(station.get("connections"))) {
				connections.add(String.valueOf(connection));
			}
			newStations.put(hex, new Station(hex, Json.string(station, "name", hex), (int) Json.number(station, "color", 0), connections));
		}

		final Map<String, Route> newRoutes = new LinkedHashMap<>();
		final Map<String, Set<String>> depotRoutes = new LinkedHashMap<>();
		for (final Object element : Json.asArray(data.get("routes"))) {
			final Map<String, Object> route = Json.asObject(element);
			final String hex = Json.string(route, "id", "");
			if (hex.isEmpty()) {
				continue;
			}
			final List<Route.Stop> stops = new ArrayList<>();
			for (final Object stopElement : Json.asArray(route.get("stations"))) {
				final Map<String, Object> stop = Json.asObject(stopElement);
				stops.add(new Route.Stop(
					Ids.normalize(Json.string(stop, "id", "0")),
					Json.string(stop, "name", ""),
					(int) Json.number(stop, "x", 0),
					(int) Json.number(stop, "y", 0),
					(int) Json.number(stop, "z", 0),
					Json.number(stop, "dwellTime", 0)));
			}
			final List<Long> durations = new ArrayList<>();
			for (final Object duration : Json.asArray(route.get("durations"))) {
				durations.add(duration instanceof Number number ? number.longValue() : 0L);
			}
			final List<String> depotNames = new ArrayList<>();
			for (final Object depot : Json.asArray(route.get("depots"))) {
				depotNames.add(String.valueOf(depot));
				depotRoutes.computeIfAbsent(String.valueOf(depot), key -> new LinkedHashSet<>()).add(hex);
			}
			newRoutes.put(hex, new Route(hex, Json.string(route, "name", hex), Json.string(route, "number", ""),
				(int) Json.number(route, "color", 0), Json.string(route, "type", "TRAIN"),
				Json.string(route, "circularState", "NONE"), stops, durations, depotNames));
		}

		final Map<String, Depot> newDepots = new LinkedHashMap<>();
		depotRoutes.forEach((name, routeIds) -> newDepots.put("depot:" + name, new Depot(null, name, new ArrayList<>(routeIds), List.of())));

		stations = newStations;
		routes = newRoutes;
		depots = newDepots;
		discoverPlatforms(now);
		Log.info("topology refreshed", "stations", newStations.size(), "routes", newRoutes.size(), "depots", newDepots.size(), "platforms", platformCache.size());
	}

	/**
	 * One arrivals query per station with a tiny cap: the only way the stock API reveals which
	 * platform belongs to which station. Runs on the topology cadence, never per poll.
	 */
	private void discoverPlatforms(long now) {
		final Map<String, String> discovered = new ConcurrentHashMap<>();
		final Map<String, Platform> platforms = new ConcurrentHashMap<>();
		for (final Station station : stations.values()) {
			try {
				final Map<String, Object> request = new LinkedHashMap<>();
				request.put("stationIdsHex", List.of(station.hex()));
				request.put("maxCountPerPlatform", 1);
				request.put("maxCountTotal", 8);
				final Map<String, Object> response = Json.asObject(http.post(baseUrl + "/mtr/api/map/arrivals?dimension=" + dimension, request));
				for (final Object element : Json.asArray(response.get("arrivals"))) {
					final Map<String, Object> arrival = Json.asObject(element);
					final long platformId = Json.number(arrival, "platformId", 0);
					if (platformId == 0) {
						continue;
					}
					final String hex = Ids.hex(platformId);
					discovered.put(hex, station.hex());
					platforms.putIfAbsent(hex, new Platform(platformId, hex, Json.string(arrival, "platformName", hex), station.hex(), 0));
				}
			} catch (Exception exception) {
				HttpJson.logDebugFailure("discover-platforms " + station.hex(), exception);
			}
		}
		mergeCatalogue(discovered, platforms);
		platformToStation.clear();
		platformToStation.putAll(discovered);
		platformCache.clear();
		platformCache.putAll(platforms);
	}

	/**
	 * Merges operator declared platforms into the discovered set. Without this, an idle
	 * alternative platform is invisible to the selector because the stock arrivals endpoint
	 * only ever mentions platforms that already have a scheduled train.
	 */
	private void mergeCatalogue(Map<String, String> discovered, Map<String, Platform> platforms) {
		final String catalogue = config.get("catalogue.platforms");
		if (catalogue == null || catalogue.isBlank()) {
			return;
		}
		for (final String entry : catalogue.split(";")) {
			final int equals = entry.indexOf('=');
			if (equals <= 0) {
				continue;
			}
			final String stationHex = Ids.normalize(entry.substring(0, equals).trim());
			for (final String platformHexRaw : entry.substring(equals + 1).trim().split("\\|")) {
				final String platformHex = Ids.normalize(platformHexRaw.trim());
				if (platformHex.isEmpty() || "0000000000000000".equals(platformHex)) {
					continue;
				}
				discovered.put(platformHex, stationHex);
				platforms.putIfAbsent(platformHex, new Platform(Ids.parse(platformHex, 0L), platformHex, platformHex, stationHex, 0));
			}
		}
	}

	private void collectArrivals(Map<String, Vehicle> vehicles, long stamp) {
		final Map<String, Object> request = new LinkedHashMap<>();
		request.put("maxCountPerPlatform", config.getInt("tsc.arrivals-max-per-platform"));
		request.put("maxCountTotal", config.getInt("tsc.arrivals-max-total"));
		if (!platformToStation.isEmpty()) {
			request.put("platformIdsHex", new ArrayList<>(platformToStation.keySet()));
		} else {
			request.put("stationIdsHex", new ArrayList<>(stations.keySet()));
		}

		final Map<String, Object> response;
		try {
			response = Json.asObject(http.post(baseUrl + "/mtr/api/map/arrivals?dimension=" + dimension, request));
		} catch (Exception exception) {
			throw new RuntimeException("arrivals query failed: " + exception.getMessage(), exception);
		}

		for (final Object element : Json.asArray(response.get("arrivals"))) {
			final Map<String, Object> arrival = Json.asObject(element);
			final long platformId = Json.number(arrival, "platformId", 0);
			final String platformHex = platformId == 0 ? "" : Ids.hex(platformId);
			final String stationHex = platformToStation.getOrDefault(platformHex, "");
			final String routeHex = Ids.hex(Json.number(arrival, "routeId", 0));

			final Vehicle vehicle = new Vehicle();
			vehicle.origin = name();
			vehicle.controllable = false;
			vehicle.routeHex = routeHex;
			vehicle.routeNumber = Json.string(arrival, "routeNumber", "");
			vehicle.platformHex = platformHex;
			vehicle.stationHex = stationHex;
			vehicle.destination = Json.string(arrival, "destination", "");
			vehicle.etaMillis = Json.number(arrival, "arrival", stamp);
			vehicle.departureMillis = Json.number(arrival, "departure", vehicle.etaMillis);
			vehicle.deviationMillis = Json.number(arrival, "deviation", 0);
			vehicle.serviceStartMillis = 0L;
			vehicle.atPlatform = vehicle.etaMillis <= stamp;
			vehicle.inDepot = false;

			final List<Object> cars = Json.asArray(arrival.get("cars"));
			double passengers = 0;
			String vehicleId = "";
			for (final Object carElement : cars) {
				final Map<String, Object> car = Json.asObject(carElement);
				passengers += Json.decimal(car, "passengerCount", 0);
				if (vehicleId.isEmpty()) {
					vehicleId = Json.string(car, "vehicleId", "");
				}
			}
			vehicle.passengerCount = passengers;
			vehicle.id = vehicleId.isEmpty()
				? "slot:" + platformHex + ":" + routeHex + ":" + Json.number(arrival, "departureIndex", -1)
				: "vehicle:" + vehicleId;
			vehicle.name = vehicleId.isEmpty() ? vehicle.id : vehicleId;

			vehicle.dwellElapsedMillis = Math.max(0, stamp - vehicle.etaMillis);
			final Route route = routes.get(routeHex);
			if (route != null && vehicle.departureMillis <= vehicle.etaMillis) {
				vehicle.departureMillis = vehicle.etaMillis + Math.max(0, averageDwell(route));
			}

			final Vehicle existing = vehicles.get(vehicle.id);
			if (existing == null || vehicle.etaMillis < existing.etaMillis) {
				vehicles.put(vehicle.id, vehicle);
			}
		}
	}

	private long averageDwell(Route route) {
		if (route.stops().isEmpty()) {
			return 0;
		}
		long total = 0;
		for (final Route.Stop stop : route.stops()) {
			total += stop.dwellMillis();
		}
		return total / route.stops().size();
	}

	private static String stripTrailingSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}
}
