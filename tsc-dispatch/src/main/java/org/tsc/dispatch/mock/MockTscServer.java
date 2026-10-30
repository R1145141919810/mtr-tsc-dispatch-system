package org.tsc.dispatch.mock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.tsc.dispatch.util.Json;
import org.tsc.dispatch.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A faithful-enough stand-in for a running TSC webserver.
 *
 * <p>Serves the same paths and payload shapes the service reads in production
 * ({@code /mtr/api/map/*}) and, optionally, the control bridge protocol. It exists so the
 * dispatcher can be exercised end to end without a Minecraft server: the bundled
 * {@code mock} command starts it with a small demo network, and the self test drives it over
 * real HTTP.</p>
 */
public final class MockTscServer implements AutoCloseable {

	private static final long STATION_CENTRAL = 0x0000000000000001L;
	private static final long STATION_NORTH = 0x0000000000000002L;
	private static final long STATION_SOUTH = 0x0000000000000003L;
	private static final long STATION_AIRPORT = 0x0000000000000004L;

	private static final long PLATFORM_CENTRAL_1 = 0x0000000000000011L;
	private static final long PLATFORM_CENTRAL_2 = 0x0000000000000012L;
	private static final long PLATFORM_NORTH_1 = 0x0000000000000021L;
	private static final long PLATFORM_SOUTH_1 = 0x0000000000000031L;
	private static final long PLATFORM_AIRPORT_1 = 0x0000000000000041L;
	private static final long PLATFORM_AIRPORT_2 = 0x0000000000000042L;

	private static final long ROUTE_LOOP = 0x1000000000000001L;
	private static final long ROUTE_SOUTH = 0x1000000000000002L;
	private static final String DEPOT_NAME = "Central Depot";

	private final HttpServer server;
	private final boolean bridgeEnabled;
	private final List<MockVehicle> vehicles = new ArrayList<>();
	private final AtomicLong appliedCommands = new AtomicLong();
	private final AtomicLong rejectedCommands = new AtomicLong();
	private final AtomicLong deferredCommands = new AtomicLong();
	private final List<Map<String, Object>> commandLog = java.util.Collections.synchronizedList(new ArrayList<>());
	private volatile boolean running = true;

	public MockTscServer(int port, boolean bridgeEnabled) throws IOException {
		this.bridgeEnabled = bridgeEnabled;
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		this.server.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
			final Thread thread = new Thread(runnable, "mock-tsc");
			thread.setDaemon(true);
			return thread;
		}));
		seed();
		this.server.createContext("/mtr/api/map/stations-and-routes", exchange -> respondEnvelope(exchange, stationsAndRoutes()));
		this.server.createContext("/mtr/api/map/arrivals", this::handleArrivals);
		this.server.createContext("/mtr/api/map/departures", exchange -> respondEnvelope(exchange, departures()));
		this.server.createContext("/mtr/api/map/clients", exchange -> respondEnvelope(exchange, Map.of("currentTime", System.currentTimeMillis(), "clients", List.of())));
		this.server.createContext("/dispatch/v1/capabilities", this::handleCapabilities);
		this.server.createContext("/dispatch/v1/state", this::handleState);
		this.server.createContext("/dispatch/v1/command", this::handleCommand);
	}

	public int port() {
		return server.getAddress().getPort();
	}

	public long appliedCommands() {
		return appliedCommands.get();
	}

	public long rejectedCommands() {
		return rejectedCommands.get();
	}

	public long deferredCommands() {
		return deferredCommands.get();
	}

	/** Every command body received on {@code /dispatch/v1/command}, in order. */
	public List<Map<String, Object>> commands() {
		return List.copyOf(commandLog);
	}

	public synchronized List<MockVehicle> vehicles() {
		return List.copyOf(vehicles);
	}

	public synchronized MockVehicle vehicle(String id) {
		for (final MockVehicle vehicle : vehicles) {
			if (vehicle.id.equals(id)) {
				return vehicle;
			}
		}
		return null;
	}

	public void start() {
		server.start();
		final Thread ticker = new Thread(() -> {
			while (running) {
				try {
					Thread.sleep(1_000);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					return;
				}
				synchronized (MockTscServer.this) {
					vehicles.forEach(MockVehicle::advance);
				}
			}
		}, "mock-tsc-sim");
		ticker.setDaemon(true);
		ticker.start();
		Log.info("mock tsc server started", "port", port(), "bridge", bridgeEnabled);
	}

	@Override
	public void close() {
		running = false;
		server.stop(0);
	}

	// ------------------------------------------------------------------ seed data

	private void seed() {
		final long now = System.currentTimeMillis();
		vehicles.add(new MockVehicle("1001", "airport-express", ROUTE_LOOP, PLATFORM_AIRPORT_1, STATION_AIRPORT, now + 40_000, now + 75_000, 41));
		// 1002 is parked at the depot and plans to depart towards Airport 1: the case the bridge
		// can actually re-platform.
		final MockVehicle parked = new MockVehicle("1002", "south-shuttle", ROUTE_SOUTH, PLATFORM_AIRPORT_1, STATION_AIRPORT, now + 30_000, now + 50_000, 12);
		parked.inDepot = true;
		vehicles.add(parked);
		vehicles.add(new MockVehicle("1003", "loop-city", ROUTE_LOOP, PLATFORM_NORTH_1, STATION_NORTH, now + 25_000, now + 60_000, 0));
	}

	/** Minimal mutable vehicle used by the mock world. */
	public static final class MockVehicle {
		public final String id;
		public final String name;
		public final long routeId;
		public long platformId;
		public long stationId;
		public long etaMillis;
		public long departureMillis;
		public long deviationMillis;
		public double passengers;
		public boolean inDepot;
		public int holdSeconds;

		MockVehicle(String id, String name, long routeId, long platformId, long stationId, long etaMillis, long departureMillis, double passengers) {
			this.id = id;
			this.name = name;
			this.routeId = routeId;
			this.platformId = platformId;
			this.stationId = stationId;
			this.etaMillis = etaMillis;
			this.departureMillis = departureMillis;
			this.passengers = passengers;
		}

		void advance() {
			if (inDepot || etaMillis > System.currentTimeMillis()) {
				return;
			}
			// Arrived: dwell briefly, then continue towards the next platform of the route.
			if (starboard()) {
				etaMillis = System.currentTimeMillis() + 90_000;
				departureMillis = etaMillis + 20_000;
			}
		}

		private boolean starboard() {
			return true;
		}
	}

	private Map<String, Object> stationsAndRoutes() {
		final List<Object> stations = new ArrayList<>();
		stations.add(station(STATION_CENTRAL, "Central"));
		stations.add(station(STATION_NORTH, "North"));
		stations.add(station(STATION_SOUTH, "South"));
		stations.add(station(STATION_AIRPORT, "Airport"));

		final List<Object> routes = new ArrayList<>();
		routes.add(route(ROUTE_LOOP, "Airport Express", "A", new long[]{STATION_CENTRAL, STATION_NORTH, STATION_AIRPORT},
			new long[]{60_000, 90_000, 120_000}, "CLOCKWISE", true));
		routes.add(route(ROUTE_SOUTH, "South Shuttle", "S", new long[]{STATION_CENTRAL, STATION_SOUTH, STATION_AIRPORT},
			new long[]{70_000, 100_000}, "NONE", false));

		final Map<String, Object> data = new LinkedHashMap<>();
		data.put("stations", stations);
		data.put("routes", routes);
		data.put("dimensions", List.of("minecraft:overworld"));
		return data;
	}

	private static Map<String, Object> station(long id, String name) {
		final Map<String, Object> station = new LinkedHashMap<>();
		station.put("id", hex(id));
		station.put("name", name);
		station.put("color", 0);
		station.put("connections", List.of());
		return station;
	}

	private static Map<String, Object> route(long id, String name, String number, long[] stops, long[] durations,
											 String circularState, boolean circular) {
		final List<Object> stopList = new ArrayList<>();
		long x = 0;
		for (final long stop : stops) {
			final Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", hex(stop));
			item.put("name", stationName(stop));
			item.put("x", x);
			x += 200;
			item.put("y", 64);
			item.put("z", 0);
			item.put("dwellTime", 20_000);
			stopList.add(item);
		}
		final List<Object> durationList = new ArrayList<>();
		for (final long duration : durations) {
			durationList.add(duration);
		}
		final Map<String, Object> route = new LinkedHashMap<>();
		route.put("id", hex(id));
		route.put("name", name);
		route.put("color", 0x3366ff);
		route.put("number", number);
		route.put("type", "TRAIN");
		route.put("circularState", circular ? circularState : "NONE");
		route.put("hidden", false);
		route.put("stations", stopList);
		route.put("durations", durationList);
		route.put("depots", List.of(DEPOT_NAME));
		return route;
	}

	private void handleArrivals(HttpExchange exchange) throws IOException {
		final Map<String, Object> request = Json.asObject(Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
		final List<Object> stationFilter = Json.asArray(request.get("stationIdsHex"));
		final List<Object> platformFilter = Json.asArray(request.get("platformIdsHex"));
		final List<Object> arrivals = new ArrayList<>();

		synchronized (this) {
			for (final MockVehicle vehicle : vehicles) {
				if (!stationFilter.isEmpty() && !stationFilter.contains(hex(vehicle.stationId))) {
					continue;
				}
				if (!platformFilter.isEmpty() && !platformFilter.contains(hex(vehicle.platformId))) {
					continue;
				}
				final Map<String, Object> arrival = new LinkedHashMap<>();
				arrival.put("destination", stationName(STATION_AIRPORT));
				arrival.put("arrival", vehicle.etaMillis);
				arrival.put("departure", vehicle.departureMillis);
				arrival.put("deviation", vehicle.deviationMillis);
				arrival.put("realtime", true);
				arrival.put("departureIndex", 0);
				arrival.put("isTerminating", false);
				arrival.put("routeId", vehicle.routeId);
				arrival.put("routeName", vehicle.routeId == ROUTE_LOOP ? "Airport Express" : "South Shuttle");
				arrival.put("routeNumber", vehicle.routeId == ROUTE_LOOP ? "A" : "S");
				arrival.put("routeColor", 0x3366ff);
				arrival.put("circularState", "CLOCKWISE");
				arrival.put("platformId", vehicle.platformId);
				arrival.put("platformName", platformName(vehicle.platformId));
				final Map<String, Object> car = new LinkedHashMap<>();
				car.put("vehicleId", vehicle.id);
				car.put("capacity", 200);
				car.put("passengerCount", vehicle.passengers);
				arrival.put("cars", List.of(car));
				arrivals.add(arrival);
			}
		}

		final Map<String, Object> data = new LinkedHashMap<>();
		data.put("currentTime", System.currentTimeMillis());
		data.put("arrivals", arrivals);
		respondEnvelope(exchange, data);
	}

	private Map<String, Object> departures() {
		final List<Object> routes = new ArrayList<>();
		synchronized (this) {
			for (final MockVehicle vehicle : vehicles) {
				final Map<String, Object> deviation = new LinkedHashMap<>();
				deviation.put("deviation", vehicle.deviationMillis);
				deviation.put("departures", List.of(Math.max(0, vehicle.departureMillis - System.currentTimeMillis())));
				final Map<String, Object> route = new LinkedHashMap<>();
				route.put("id", hex(vehicle.routeId));
				route.put("departures", List.of(deviation));
				routes.add(route);
				break;
			}
		}
		final Map<String, Object> data = new LinkedHashMap<>();
		data.put("cachedResponseTime", System.currentTimeMillis());
		data.put("departures", routes);
		return data;
	}

	private void handleCapabilities(HttpExchange exchange) throws IOException {
		if (!bridgeEnabled) {
			respondJson(exchange, 404, Map.of("error", "bridge disabled"));
			return;
		}
		final Map<String, Object> body = new LinkedHashMap<>();
		body.put("protocol", 1);
		// Mirrors the real bridge: no reroute, no depot refresh, dwell control instead of hold.
		body.put("features", List.of("set_platform", "deploy_train", "return_to_depot", "dwell_control"));
		body.put("telemetry", List.of("vehicles", "platforms", "routes", "depots"));
		body.put("dimensions", 1);
		body.put("ready", true);
		respondJson(exchange, 200, body);
	}

	private void handleState(HttpExchange exchange) throws IOException {
		if (!bridgeEnabled) {
			respondJson(exchange, 404, Map.of("error", "bridge disabled"));
			return;
		}
		respondJson(exchange, 200, bridgeState());
	}

	private Map<String, Object> bridgeState() {
		final List<Object> stationList = new ArrayList<>();
		stationList.add(bridgeStation(STATION_CENTRAL, "Central"));
		stationList.add(bridgeStation(STATION_NORTH, "North"));
		stationList.add(bridgeStation(STATION_SOUTH, "South"));
		stationList.add(bridgeStation(STATION_AIRPORT, "Airport"));

		final List<Object> platformList = new ArrayList<>();
		platformList.add(bridgePlatform(PLATFORM_CENTRAL_1, "Central 1", STATION_CENTRAL));
		platformList.add(bridgePlatform(PLATFORM_CENTRAL_2, "Central 2", STATION_CENTRAL));
		platformList.add(bridgePlatform(PLATFORM_NORTH_1, "North 1", STATION_NORTH));
		platformList.add(bridgePlatform(PLATFORM_SOUTH_1, "South 1", STATION_SOUTH));
		platformList.add(bridgePlatform(PLATFORM_AIRPORT_1, "Airport 1", STATION_AIRPORT));
		platformList.add(bridgePlatform(PLATFORM_AIRPORT_2, "Airport 2", STATION_AIRPORT));

		final List<Object> routeList = new ArrayList<>();
		routeList.add(bridgeRoute(ROUTE_LOOP, "Airport Express", "A",
			new long[]{STATION_CENTRAL, STATION_NORTH, STATION_AIRPORT}, new long[]{60_000, 90_000, 120_000}, true));
		routeList.add(bridgeRoute(ROUTE_SOUTH, "South Shuttle", "S",
			new long[]{STATION_CENTRAL, STATION_SOUTH, STATION_AIRPORT}, new long[]{70_000, 100_000}, false));

		final List<Object> depotList = new ArrayList<>();
		final Map<String, Object> depot = new LinkedHashMap<>();
		depot.put("id", hex(0x2000000000000001L));
		depot.put("name", DEPOT_NAME);
		depot.put("routeIds", List.of(hex(ROUTE_LOOP), hex(ROUTE_SOUTH)));
		depot.put("sidingIds", List.of("s1", "s2"));
		depotList.add(depot);

		final List<Object> vehicleList = new ArrayList<>();
		synchronized (this) {
			for (final MockVehicle vehicle : vehicles) {
				final Map<String, Object> item = new LinkedHashMap<>();
				item.put("id", vehicle.id);
				item.put("name", vehicle.name);
				item.put("routeId", hex(vehicle.routeId));
				item.put("nextPlatformId", hex(vehicle.platformId));
				item.put("stationId", hex(vehicle.stationId));
				item.put("eta", vehicle.etaMillis);
				item.put("departure", vehicle.departureMillis);
				item.put("deviation", vehicle.deviationMillis);
				item.put("passengers", vehicle.passengers);
				item.put("controllable", true);
				item.put("atPlatform", !vehicle.inDepot && vehicle.etaMillis <= System.currentTimeMillis());
				item.put("inDepot", vehicle.inDepot);
				item.put("depot", DEPOT_NAME);
				vehicleList.add(item);
			}
		}

		final Map<String, Object> state = new LinkedHashMap<>();
		state.put("protocol", 1);
		state.put("serverTime", System.currentTimeMillis());
		state.put("stations", stationList);
		state.put("platforms", platformList);
		state.put("routes", routeList);
		state.put("depots", depotList);
		state.put("vehicles", vehicleList);
		return state;
	}

	private void handleCommand(HttpExchange exchange) throws IOException {
		if (!bridgeEnabled) {
			respondJson(exchange, 404, Map.of("error", "bridge disabled"));
			return;
		}
		final Map<String, Object> command = Json.asObject(Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
		final String kind = Json.string(command, "kind", "");
		final String vehicleId = Json.string(command, "vehicleId", "");
		final Map<String, Object> args = Json.asObject(command.get("args"));

		synchronized (this) {
			commandLog.add(command);
			final MockVehicle vehicle = vehicle(vehicleId);
			if (vehicle == null) {
				rejectedCommands.incrementAndGet();
				respondJson(exchange, 200, Map.of("ok", false, "applied", false, "retryable", false,
					"outcome", "REJECTED", "detail", "unknown vehicle"));
				return;
			}
			switch (kind) {
				case "set_platform" -> {
					final long platformId = parseHex(Json.string(args, "platformId", ""));
					if (platformId == 0) {
						rejectedCommands.incrementAndGet();
						respondJson(exchange, 200, Map.of("ok", false, "applied", false, "retryable", false,
							"outcome", "REJECTED", "detail", "unknown platform"));
						return;
					}
					if (!vehicle.inDepot) {
						// Real bridge behaviour: a running train's path is frozen, so the change is
						// only an intent for the next siding visit.
						deferredCommands.incrementAndGet();
						respondJson(exchange, 200, Map.of("ok", true, "applied", false, "retryable", false,
							"outcome", "DEFERRED", "detail", "train is running; the path is frozen"));
						return;
					}
					vehicle.platformId = platformId;
					appliedCommands.incrementAndGet();
					respondJson(exchange, 200, Map.of("ok", true, "applied", true, "outcome", "APPLIED",
						"detail", "platform switched to " + platformName(platformId)));
				}
				case "dwell_control" -> {
					final int seconds = (int) Json.number(args, "seconds", 0);
					vehicle.holdSeconds = seconds;
					appliedCommands.incrementAndGet();
					respondJson(exchange, 200, Map.of("ok", true, "applied", true, "outcome", "APPLIED",
						"detail", seconds == 0 ? "dwell hold released" : "dwell extended by " + seconds + "s"));
				}
				case "return_to_depot" -> {
					vehicle.inDepot = true;
					appliedCommands.incrementAndGet();
					respondJson(exchange, 200, Map.of("ok", true, "applied", true, "outcome", "APPLIED",
						"detail", "parked at " + Json.string(args, "sidingId", "siding")));
				}
				case "deploy_train" -> {
					vehicle.inDepot = false;
					appliedCommands.incrementAndGet();
					respondJson(exchange, 200, Map.of("ok", true, "applied", true, "outcome", "APPLIED",
						"detail", "departed"));
				}
				default -> {
					rejectedCommands.incrementAndGet();
					respondJson(exchange, 200, Map.of("ok", false, "applied", false, "retryable", false,
						"outcome", "REJECTED", "detail", "unsupported kind " + kind));
				}
			}
		}
	}

	private static Map<String, Object> bridgeStation(long id, String name) {
		final Map<String, Object> station = new LinkedHashMap<>();
		station.put("id", hex(id));
		station.put("name", name);
		station.put("color", 0);
		return station;
	}

	private static Map<String, Object> bridgePlatform(long id, String name, long stationId) {
		final Map<String, Object> platform = new LinkedHashMap<>();
		platform.put("id", hex(id));
		platform.put("name", name);
		platform.put("stationId", hex(stationId));
		platform.put("dwellMillis", 20_000);
		return platform;
	}

	private static Map<String, Object> bridgeRoute(long id, String name, String number, long[] stops, long[] durations, boolean circular) {
		final List<Object> stopList = new ArrayList<>();
		long x = 0;
		for (final long stop : stops) {
			final Map<String, Object> item = new LinkedHashMap<>();
			item.put("stationId", hex(stop));
			item.put("name", stationName(stop));
			item.put("x", x);
			x += 200;
			item.put("y", 64);
			item.put("z", 0);
			item.put("dwellMillis", 20_000);
			stopList.add(item);
		}
		final List<Object> durationList = new ArrayList<>();
		for (final long duration : durations) {
			durationList.add(duration);
		}
		final Map<String, Object> route = new LinkedHashMap<>();
		route.put("id", hex(id));
		route.put("name", name);
		route.put("number", number);
		route.put("color", 0x3366ff);
		route.put("type", "TRAIN");
		route.put("circularState", circular ? "CLOCKWISE" : "NONE");
		route.put("stops", stopList);
		route.put("durations", durationList);
		route.put("depots", List.of(DEPOT_NAME));
		return route;
	}

	// ------------------------------------------------------------------ plumbing

	private static String stationName(long id) {
		return switch ((int) id) {
			case 1 -> "Central";
			case 2 -> "North";
			case 3 -> "South";
			case 4 -> "Airport";
			default -> "Unknown";
		};
	}

	private static String platformName(long id) {
		return switch ((int) id) {
			case 0x11 -> "Central 1";
			case 0x12 -> "Central 2";
			case 0x21 -> "North 1";
			case 0x31 -> "South 1";
			case 0x41 -> "Airport 1";
			case 0x42 -> "Airport 2";
			default -> "Platform";
		};
	}

	private static String hex(long value) {
		return String.format("%016x", value);
	}

	private static long parseHex(String value) {
		try {
			return Long.parseUnsignedLong(value, 16);
		} catch (Exception exception) {
			return 0L;
		}
	}

	private static void respondEnvelope(HttpExchange exchange, Map<String, Object> data) throws IOException {
		final Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("status", 200);
		envelope.put("text", "OK");
		envelope.put("currentTime", System.currentTimeMillis());
		envelope.put("data", data);
		response(exchange, 200, envelope);
	}

	private static void respondJson(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
		response(exchange, status, body);
	}

	private static void response(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
		final byte[] payload = Json.write(body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, payload.length);
		try (OutputStream output = exchange.getResponseBody()) {
			output.write(payload);
		}
	}
}
