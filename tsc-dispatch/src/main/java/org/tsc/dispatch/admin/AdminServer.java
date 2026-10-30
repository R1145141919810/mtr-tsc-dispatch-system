package org.tsc.dispatch.admin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.tsc.dispatch.config.Config;
import org.tsc.dispatch.dispatch.Command;
import org.tsc.dispatch.dispatch.Decision;
import org.tsc.dispatch.graph.GraphBuilder;
import org.tsc.dispatch.graph.RailGraph;
import org.tsc.dispatch.model.Platform;
import org.tsc.dispatch.model.Vehicle;
import org.tsc.dispatch.model.WorldSnapshot;
import org.tsc.dispatch.plan.CostModel;
import org.tsc.dispatch.plan.Occupancy;
import org.tsc.dispatch.plan.Router;
import org.tsc.dispatch.runtime.DimensionRuntime;
import org.tsc.dispatch.util.Ids;
import org.tsc.dispatch.util.Json;
import org.tsc.dispatch.util.Log;
import org.tsc.dispatch.util.Metrics;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Management API and dashboard.
 *
 * <p>Every read endpoint accepts {@code ?dimension=<index>} and defaults to the first enabled
 * dimension, because with isolated runtimes "the" state is ambiguous. The aggregate endpoints
 * ({@code /v1/status}, {@code /v1/dimensions}) report every dimension at once.</p>
 */
public final class AdminServer implements AutoCloseable {

	private final Config config;
	private final Metrics metrics;
	private final Supplier<List<DimensionRuntime>> runtimes;
	private final Supplier<Map<String, Object>> healthSupplier;
	private final Supplier<Map<String, Object>> channelsSupplier;
	private final Supplier<Map<String, Object>> validationSupplier;
	private final HttpServer server;

	public AdminServer(Config config, Metrics metrics, Supplier<List<DimensionRuntime>> runtimes,
					   Supplier<Map<String, Object>> healthSupplier, Supplier<Map<String, Object>> channelsSupplier,
					   Supplier<Map<String, Object>> validationSupplier) throws IOException {
		this.config = config;
		this.metrics = metrics;
		this.runtimes = runtimes;
		this.healthSupplier = healthSupplier;
		this.channelsSupplier = channelsSupplier;
		this.validationSupplier = validationSupplier;
		this.server = HttpServer.create(new InetSocketAddress(config.get("admin.host"), config.getInt("admin.port")), 0);
		this.server.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
			final Thread thread = new Thread(runnable, "admin-http");
			thread.setDaemon(true);
			return thread;
		}));
		this.server.createContext("/", this::handle);
	}

	public void start() {
		server.start();
		Log.info("management api listening", "url", "http://" + config.get("admin.host") + ":" + config.getInt("admin.port"));
	}

	@Override
	public void close() {
		server.stop(0);
	}

	private void handle(HttpExchange exchange) {
		try {
			final String path = exchange.getRequestURI().getPath();
			if (!authorized(exchange)) {
				respond(exchange, 401, error("unauthorized", "missing or invalid bearer token"));
				return;
			}
			if (path.equals("/") || path.equals("/index.html")) {
				respondText(exchange, 200, "text/html; charset=utf-8", Dashboard.HTML);
				return;
			}
			switch (path) {
				case "/healthz" -> respond(exchange, 200, healthSupplier.get());
				case "/readyz" -> {
					final boolean ready = primary() != null && primary().snapshot() != null;
					final Map<String, Object> body = new LinkedHashMap<>();
					body.put("ready", ready);
					body.put("dimensions", runtimes.get().size());
					respond(exchange, ready ? 200 : 503, body);
				}
				case "/v1/status" -> respond(exchange, 200, status());
				case "/v1/dimensions" -> respond(exchange, 200, dimensions());
				case "/v1/channels" -> respond(exchange, 200, channelsSupplier.get());
				case "/v1/config" -> handleConfig(exchange);
				case "/v1/config/validate" -> respond(exchange, 200, validationSupplier.get());
				case "/v1/metrics" -> respondText(exchange, 200, "text/plain; charset=utf-8", metrics.render());
				case "/v1/vehicles" -> respond(exchange, 200, vehicles(exchange));
				case "/v1/platforms" -> respond(exchange, 200, platforms(exchange));
				case "/v1/decisions" -> respond(exchange, 200, decisions(exchange));
				case "/v1/plan" -> handlePlan(exchange);
				case "/v1/command" -> handleCommand(exchange);
				case "/v1/graph" -> handleGraph(exchange);
				default -> respond(exchange, 404, error("not_found", path));
			}
		} catch (Exception exception) {
			Log.error("admin request failed", exception, "path", exchange.getRequestURI().getPath());
			try {
				respond(exchange, 500, error("internal_error", exception.getClass().getSimpleName() + ": " + exception.getMessage()));
			} catch (Exception ignored) {
				// response already committed
			}
		}
	}

	private boolean authorized(HttpExchange exchange) {
		final String token = config.get("admin.token");
		if (token == null || token.isEmpty()) {
			return true;
		}
		final String header = exchange.getRequestHeaders().getFirst("Authorization");
		return header != null && header.startsWith("Bearer ") && header.substring(7).trim().equals(token);
	}

	private DimensionRuntime primary() {
		final List<DimensionRuntime> all = runtimes.get();
		return all.isEmpty() ? null : all.get(0);
	}

	private DimensionRuntime select(HttpExchange exchange) {
		final int requested = queryInt(exchange, "dimension", Integer.MIN_VALUE);
		final List<DimensionRuntime> all = runtimes.get();
		if (requested == Integer.MIN_VALUE) {
			return all.isEmpty() ? null : all.get(0);
		}
		for (final DimensionRuntime runtime : all) {
			if (runtime.index() == requested) {
				return runtime;
			}
		}
		return null;
	}

	private Map<String, Object> status() {
		final Map<String, Object> body = new LinkedHashMap<>();
		body.put("service", config.get("service.name"));
		body.put("version", Config.VERSION);
		body.put("health", healthSupplier.get());
		body.put("channels", channelsSupplier.get());
		body.put("validation", validationSupplier.get());
		body.put("dimensions", dimensions().get("dimensions"));
		final DimensionRuntime first = primary();
		if (first != null) {
			body.put("world", first.snapshot() == null ? Map.of() : first.snapshot().summary());
			body.put("dispatch", first.dispatcher() == null ? Map.of() : first.dispatcher().status());
			body.put("bus", first.bus() == null ? Map.of() : first.bus().describe());
			body.put("compatibility", first.compatibility() == null ? Map.of() : first.compatibility().toJson());
		}
		body.put("config", config.describe());
		return body;
	}

	private Map<String, Object> dimensions() {
		final List<Object> list = new ArrayList<>();
		for (final DimensionRuntime runtime : runtimes.get()) {
			final Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", runtime.spec().id());
			item.put("index", runtime.index());
			item.put("health", runtime.health());
			item.put("compatibility", runtime.compatibility() == null ? Map.of() : runtime.compatibility().toJson());
			item.put("world", runtime.snapshot() == null ? Map.of() : runtime.snapshot().summary());
			list.add(item);
		}
		final Map<String, Object> body = new LinkedHashMap<>();
		body.put("count", list.size());
		body.put("dimensions", list);
		return body;
	}

	private Map<String, Object> vehicles(HttpExchange exchange) {
		final DimensionRuntime runtime = select(exchange);
		final List<Object> list = new ArrayList<>();
		if (runtime != null && runtime.snapshot() != null) {
			for (final Vehicle vehicle : runtime.snapshot().vehicles) {
				final Map<String, Object> item = new LinkedHashMap<>();
				item.put("id", vehicle.id);
				item.put("route", vehicle.routeHex);
				item.put("station", vehicle.stationHex);
				item.put("platform", vehicle.platformHex);
				item.put("eta", vehicle.etaMillis);
				item.put("deviation", vehicle.deviationMillis);
				item.put("passengers", vehicle.passengerCount);
				item.put("controllable", vehicle.controllable);
				item.put("inDepot", vehicle.inDepot);
				item.put("origin", vehicle.origin);
				list.add(item);
			}
		}
		final Map<String, Object> body = new LinkedHashMap<>();
		body.put("dimension", runtime == null ? -1 : runtime.index());
		body.put("count", list.size());
		body.put("vehicles", list);
		return body;
	}

	private Map<String, Object> platforms(HttpExchange exchange) {
		final DimensionRuntime runtime = select(exchange);
		final List<Object> list = new ArrayList<>();
		if (runtime != null && runtime.snapshot() != null) {
			for (final Platform platform : runtime.snapshot().platforms.values()) {
				final Map<String, Object> item = new LinkedHashMap<>();
				item.put("id", platform.hex());
				item.put("name", platform.name());
				item.put("station", platform.stationHex());
				final List<String> occupants = new ArrayList<>();
				runtime.snapshot().vehiclesAt(platform.hex()).forEach(vehicle -> occupants.add(vehicle.id));
				item.put("vehicles", occupants);
				item.put("leasedBy", runtime.leases().holder("platform:" + platform.hex()));
				list.add(item);
			}
		}
		final Map<String, Object> body = new LinkedHashMap<>();
		body.put("platforms", list);
		body.put("platform_allow", config.platformPolicy().describe());
		return body;
	}

	private Map<String, Object> decisions(HttpExchange exchange) {
		final DimensionRuntime runtime = select(exchange);
		final int limit = queryInt(exchange, "limit", 50);
		final Map<String, Object> body = new LinkedHashMap<>();
		final List<Object> list = runtime == null || runtime.dispatcher() == null
			? List.of()
			: runtime.dispatcher().recentJson(limit);
		body.put("dimension", runtime == null ? -1 : runtime.index());
		// total = cumulative since boot, returned = size of this page; the two differ on purpose,
		// because the buffer keeps only the newest entries.
		body.put("total", runtime == null || runtime.dispatcher() == null ? 0L : runtime.dispatcher().totalDecisions());
		body.put("returned", list.size());
		body.put("decisions", list);
		return body;
	}

	private void handleConfig(HttpExchange exchange) throws IOException {
		if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			respond(exchange, 200, config.describe());
			return;
		}
		final Map<String, Object> body = Json.asObject(Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
		final Map<String, Object> applied = new LinkedHashMap<>();
		final Map<String, Object> rejected = new LinkedHashMap<>();
		body.forEach((key, value) -> {
			if (config.override(key, String.valueOf(value))) {
				applied.put(key, value);
			} else {
				rejected.put(key, "unknown or structural key");
			}
		});
		final Map<String, Object> response = new LinkedHashMap<>();
		response.put("applied", applied);
		response.put("rejected", rejected);
		response.put("note", "runtime overrides are in-memory only; edit dispatch.yml to persist");
		respond(exchange, 200, response);
	}

	private void handlePlan(HttpExchange exchange) throws IOException {
		final Map<String, Object> body = Json.asObject(Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
		final DimensionRuntime runtime = select(exchange);
		if (runtime == null || runtime.snapshot() == null) {
			respond(exchange, 503, error("no_snapshot", "no telemetry received yet"));
			return;
		}
		final WorldSnapshot snapshot = runtime.snapshot();
		final RailGraph graph = GraphBuilder.build(snapshot, config);
		final Router router = new Router(graph, new CostModel(config, snapshot));
		final Occupancy occupancy = Occupancy.from(snapshot);
		for (final org.tsc.dispatch.dispatch.LeaseTable.Lease lease : runtime.leases().snapshot()) {
			occupancy.withLease(lease.resource(), lease.vehicleId(), System.currentTimeMillis(), lease.expiresAtMillis());
		}
		final Map<String, Integer> counts = new LinkedHashMap<>();
		snapshot.platforms.values().forEach(platform -> counts.merge(platform.stationHex(), 1, Integer::sum));
		final String self = Json.string(body, "vehicleId", "");
		final Set<String> avoid = new LinkedHashSet<>();
		for (final Object node : Json.asArray(body.get("avoid"))) {
			avoid.add(RailGraph.stationNode(Ids.normalize(String.valueOf(node))));
		}
		final Router.PlanContext context = new Router.PlanContext(self, occupancy, counts, avoid, 0);
		final String from = RailGraph.stationNode(Ids.normalize(Json.string(body, "fromStation", "")));
		final String toRaw = Json.string(body, "toStation", "");
		final String toDepot = Json.string(body, "toDepot", "");
		final long depart = Json.number(body, "departAt", snapshot.serverTimeMillis);
		final Map<String, Object> response = new LinkedHashMap<>();
		if (!toDepot.isEmpty()) {
			response.put("plan", router.planToNearestDepot(from, depart, context).toJson());
		} else if (!toRaw.isEmpty()) {
			response.put("plan", router.plan(from, RailGraph.stationNode(Ids.normalize(toRaw)), depart, context).toJson());
		} else {
			respond(exchange, 400, error("bad_request", "toStation or toDepot is required"));
			return;
		}
		respond(exchange, 200, response);
	}

	private void handleCommand(HttpExchange exchange) throws IOException {
		final Map<String, Object> body = Json.asObject(Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
		final DimensionRuntime runtime = select(exchange);
		if (runtime == null || runtime.snapshot() == null) {
			respond(exchange, 503, error("no_snapshot", "no telemetry received yet"));
			return;
		}
		final Command.Kind kind = Command.Kind.fromWire(Json.string(body, "kind", ""));
		if (kind == null) {
			respond(exchange, 400, error("bad_request", "unknown kind"));
			return;
		}
		final WorldSnapshot snapshot = runtime.snapshot();
		final Command command = new Command();
		command.kind = kind;
		command.vehicleId = Json.string(body, "vehicleId", "");
		command.args.putAll(Json.asObject(body.get("args")));
		command.reason = Json.string(body, "reason", "manual");
		command.preconditionVehicleId = command.vehicleId;
		final Vehicle vehicle = snapshot.vehicle(command.vehicleId);
		command.preconditionPlatformHex = vehicle == null ? "" : vehicle.platformHex;
		command.preconditionObservedAt = snapshot.fetchedAtMillis;
		command.expiresAtMillis = System.currentTimeMillis() + config.getLong("dispatch.command_ttl_seconds") * 1000L;
		command.maxAttempts = Math.max(1, config.getInt("dispatch.max_attempts"));
		command.idempotencyKey = Json.string(body, "key", "manual|" + kind.wire + "|" + command.vehicleId + "|" + System.currentTimeMillis() / 60_000L);

		final Decision decision = new Decision(System.currentTimeMillis(), "MANUAL", command.vehicleId, command.reason, true, command);
		runtime.bus().submit(decision, snapshot);
		respond(exchange, 200, decision.toJson());
	}

	private void handleGraph(HttpExchange exchange) throws IOException {
		final DimensionRuntime runtime = select(exchange);
		if (runtime == null || runtime.snapshot() == null) {
			respond(exchange, 503, error("no_snapshot", "no telemetry received yet"));
			return;
		}
		respond(exchange, 200, GraphBuilder.build(runtime.snapshot(), config).describe());
	}

	private static int queryInt(HttpExchange exchange, String name, int fallback) {
		final String query = exchange.getRequestURI().getQuery();
		if (query == null) {
			return fallback;
		}
		for (final String pair : query.split("&")) {
			final String[] parts = pair.split("=", 2);
			if (parts.length == 2 && parts[0].equals(name)) {
				try {
					return Integer.parseInt(parts[1]);
				} catch (NumberFormatException ignored) {
					return fallback;
				}
			}
		}
		return fallback;
	}

	private static Map<String, Object> error(String code, String message) {
		final Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", code);
		body.put("message", message);
		return body;
	}

	private static void respond(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
		respondText(exchange, status, "application/json; charset=utf-8", Json.write(body));
	}

	private static void respondText(HttpExchange exchange, int status, String contentType, String body) throws IOException {
		final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(status, payload.length);
		try (OutputStream output = exchange.getResponseBody()) {
			output.write(payload);
		}
	}
}
