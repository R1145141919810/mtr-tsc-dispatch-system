package org.tsc.dispatch;

import org.tsc.dispatch.actuator.Actuator;
import org.tsc.dispatch.actuator.BridgeActuator;
import org.tsc.dispatch.actuator.FailoverActuator;
import org.tsc.dispatch.actuator.RconActuator;
import org.tsc.dispatch.config.Config;
import org.tsc.dispatch.config.ConfigValidator;
import org.tsc.dispatch.dispatch.Command;
import org.tsc.dispatch.dispatch.CommandBus;
import org.tsc.dispatch.dispatch.Decision;
import org.tsc.dispatch.dispatch.Dispatcher;
import org.tsc.dispatch.dispatch.LeaseTable;
import org.tsc.dispatch.mock.MockRconServer;
import org.tsc.dispatch.mock.MockTscServer;
import org.tsc.dispatch.model.Depot;
import org.tsc.dispatch.model.Platform;
import org.tsc.dispatch.model.Route;
import org.tsc.dispatch.model.Station;
import org.tsc.dispatch.model.Vehicle;
import org.tsc.dispatch.model.WorldSnapshot;
import org.tsc.dispatch.persist.StateStore;
import org.tsc.dispatch.runtime.DimensionRuntime;
import org.tsc.dispatch.source.CompatibilityProbe;
import org.tsc.dispatch.source.HttpJson;
import org.tsc.dispatch.source.PortDiscovery;
import org.tsc.dispatch.util.Ids;
import org.tsc.dispatch.util.Metrics;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scenarios for the seven configuration areas: YAML loading, version gating, platform
 * allow-set, depot capacity, RCON failover, dimension isolation, adaptive polling and port
 * discovery.
 */
public final class ConfigScenarios {

	public record Result(int passed, int failed, List<String> failures) {
	}

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

	private int passed;
	private int failed;
	private final List<String> failures = new ArrayList<>();

	public static Result run() {
		final ConfigScenarios scenarios = new ConfigScenarios();
		scenarios.yamlLoadsAllSections();
		scenarios.versionGate();
		scenarios.platformAllowBlocksMove();
		scenarios.depotCapacityDefersReturn();
		scenarios.failoverToRcon();
		scenarios.bridgeWireMapping();
		scenarios.dimensionIsolation();
		scenarios.adaptivePolling();
		scenarios.portDiscovery();
		return new Result(scenarios.passed, scenarios.failed, scenarios.failures);
	}

	// ------------------------------------------------------------------ 1..7 schema

	private void yamlLoadsAllSections() {
		final String name = "yaml config loads all seven sections";
		try {
			final Path directory = tempDirectory("yaml");
			final Path file = directory.resolve("dispatch.yml");
			Files.writeString(file, """
				service:
				  name: yard-control
				tsc:
				  base_url: http://10.0.0.5:8899
				  timeout_ms: 1500
				  version:
				    declared: "4.0.2"
				    min: "4.0.0"
				    strict: true
				  port_discovery:
				    enabled: false
				    range_start: 9000
				    range_end: 9005
				bridge:
				  enabled: true
				  mode: bridge
				  form: mod
				  base_url: http://10.0.0.6:8890
				  features_required: [set_platform, return_to_depot]
				rcon:
				  enabled: true
				  port: 25580
				  password: "s3cret"
				  fallback_on: [bridge_offline, bridge_reject]
				dimensions:
				  - id: minecraft:overworld
				    index: 0
				    enabled: true
				  - id: minecraft:the_nether
				    index: 7
				    enabled: false
				network_scale:
				  max_trains: 5
				  adaptive: true
				  polling_min_ms: 400
				  polling_max_ms: 5000
				  tiers:
				    - max_trains: 10
				      polling_interval_ms: 1000
				    - max_trains: 50
				      polling_interval_ms: 3000
				dispatch:
				  strategy: min_waiting
				  weights:
				    travel: 3.0
				    wait:
				  constraints:
				    depot_capacity_limit: 2
				    depot_capacity:
				      "Central Depot": 4
				    route_priority:
				      "A": 2.5
				platform_allow:
				  default: deny
				  rules:
				    - route: "A"
				      station: "0000000000000004"
				      platforms: ["0000000000000041", "0000000000000042"]
				""", StandardCharsets.UTF_8);

			final Config config = Config.load(file);
			check(name, "yard-control".equals(config.get("service.name")), "service.name");
			check(name, "http://10.0.0.5:8899".equals(config.get("tsc.base_url")), "base_url");
			check(name, config.getInt("tsc.timeout_ms") == 1500, "nested timeout");
			check(name, config.getInt("tsc.timeout-ms") == 1500, "dashed lookup normalises to the YAML key");
			check(name, config.dimensions().size() == 2 && config.enabledDimensions().size() == 1, "dimensions parsed");
			check(name, config.enabledDimensions().get(0).index() == 0, "enabled dimension index");
			check(name, config.pollingInterval() == 1000, "tier by max_trains=5 -> 1000ms");
			check(name, Math.abs(config.weight("wait") - 1.4) < 0.001, "strategy preset supplies wait=1.4");
			check(name, Math.abs(config.weight("travel") - 3.0) < 0.001, "explicit weight overrides preset");
			check(name, config.depotCapacity("Central Depot") == 4 && config.depotCapacity("Other") == 2, "depot capacity");
			check(name, Math.abs(config.routePriority(Ids.hex(ROUTE_LOOP), "A") - 2.5) < 0.001, "route priority by number");
			check(name, config.rconFallbackOn().contains("bridge_offline"), "fallback triggers");
			check(name, config.bridgeFeaturesRequired().size() == 2, "required bridge features");
			check(name, config.platformPolicy().allows(Ids.hex(ROUTE_LOOP), "A", Ids.hex(STATION_AIRPORT), Ids.hex(PLATFORM_AIRPORT_2)),
				"allow-list permits Airport 2 for route A");
			check(name, !config.platformPolicy().allows(Ids.hex(ROUTE_SOUTH), "S", Ids.hex(STATION_AIRPORT), Ids.hex(PLATFORM_AIRPORT_2)),
				"route S falls back to deny");
			check(name, !ConfigValidator.hasErrors(ConfigValidator.validate(config)), "validator accepts the file");
		} catch (Exception exception) {
			fail(name, exception);
		}
	}

	private void versionGate() {
		final String name = "version gate";
		try {
			final WorldSnapshot snapshot = snapshot(List.of());
			final Config lenient = Config.defaults();
			lenient.override("tsc.version.declared", "3.9.0");
			check(name, CompatibilityProbe.evaluate(lenient, snapshot).compatible(),
				"non-strict out-of-range still starts");
			check(name, !CompatibilityProbe.evaluate(lenient, snapshot).versionInRange(), "3.9.0 is out of range");

			final Config strict = Config.defaults();
			strict.override("tsc.version.declared", "3.9.0");
			strict.override("tsc.version.strict", "true");
			check(name, !CompatibilityProbe.evaluate(strict, snapshot).compatible(), "strict rejects 3.9.0");

			final Config unknown = Config.defaults();
			unknown.override("tsc.version.strict", "true");
			check(name, !CompatibilityProbe.evaluate(unknown, snapshot).compatible(), "strict rejects unknown version");

			final Config good = Config.defaults();
			good.override("tsc.version.declared", "4.0.2");
			good.override("tsc.version.strict", "true");
			final CompatibilityProbe.Result result = CompatibilityProbe.evaluate(good, snapshot);
			check(name, result.compatible() && result.versionInRange(), "4.0.2 accepted");
		} catch (Exception exception) {
			fail(name, exception);
		}
	}

	// ------------------------------------------------------------------ 7 platform allow-set

	private void platformAllowBlocksMove() {
		final String name = "platform allow-set blocks an illegal move";
		try {
			final long now = System.currentTimeMillis();
			final Vehicle arriving = vehicle("v1", ROUTE_LOOP, PLATFORM_AIRPORT_1, STATION_AIRPORT, now + 40_000, now + 75_000, 30);
			final Vehicle parked = vehicle("v2", ROUTE_SOUTH, PLATFORM_AIRPORT_1, STATION_AIRPORT, now + 30_000, now + 50_000, 10);
			parked.inDepot = true;
			final WorldSnapshot snapshot = snapshot(List.of(arriving, parked));

			final Config open = config();
			final List<Decision> allowed = dispatcher(open).evaluate(snapshot);
			check(name, allowed.stream().anyMatch(decision -> "PLATFORM_SELECTION".equals(decision.type)),
				"without rules the move is produced");

			final Config restricted = config();
			restricted.withPlatformPolicy(new org.tsc.dispatch.config.PlatformPolicy(
				org.tsc.dispatch.config.PlatformPolicy.DefaultPolicy.DENY,
				List.of(new org.tsc.dispatch.config.PlatformRule("S", Ids.hex(STATION_AIRPORT), List.of(Ids.hex(PLATFORM_AIRPORT_1)), false))));
			final List<Decision> blocked = dispatcher(restricted).evaluate(snapshot);
			check(name, blocked.stream().noneMatch(decision -> "PLATFORM_SELECTION".equals(decision.type)),
				"with a rule that only allows Airport 1 the move is suppressed");
		} catch (Exception exception) {
			fail(name, exception);
		}
	}

	// ------------------------------------------------------------------ 6 depot capacity

	private void depotCapacityDefersReturn() {
		final String name = "depot capacity is a hard constraint";
		try {
			final long now = System.currentTimeMillis();
			final Config config = config();
			config.override("trigger.return_after_seconds", "60");
			config.override("dispatch.constraints.depot_capacity_limit", "1");

			final Vehicle returning = vehicle("v7", ROUTE_LOOP, PLATFORM_CENTRAL_1, STATION_CENTRAL, now + 5_000, now + 25_000, 0);
			returning.serviceStartMillis = now - 7_200_000L;
			final Vehicle parked = vehicle("v8", ROUTE_LOOP, PLATFORM_CENTRAL_1, STATION_CENTRAL, now + 5_000, now + 25_000, 0);
			parked.inDepot = true;
			parked.depotName = "Central Depot";

			final List<Decision> decisions = dispatcher(config).evaluate(snapshot(List.of(returning, parked)));
			final Decision decision = decisions.stream()
				.filter(candidate -> "RETURN_TO_DEPOT".equals(candidate.type) && "v7".equals(candidate.vehicleId))
				.findFirst().orElse(null);
			check(name, decision != null, "a return decision is still reported");
			check(name, decision != null && decision.command == null, "but it carries no command");
			check(name, decision != null && decision.reason.contains("capacity"), "reason mentions capacity: "
				+ (decision == null ? "none" : decision.reason));
		} catch (Exception exception) {
			fail(name, exception);
		}
	}

	// ------------------------------------------------------------------ 2/3 bridge + rcon

	private void failoverToRcon() {
		final String name = "bridge failure falls back to RCON";
		MockRconServer rconServer = null;
		try {
			rconServer = new MockRconServer(0, "s3cret");
			rconServer.start();

			final Config config = Config.defaults();
			config.override("rcon.enabled", "true");
			config.override("rcon.host", "127.0.0.1");
			config.override("rcon.port", String.valueOf(rconServer.port()));
			config.override("rcon.password", "s3cret");
			config.override("rcon.fallback_on", "bridge_offline,bridge_reject");

			final FailoverActuator actuator = new FailoverActuator(null, new RconActuator(config),
				config.rconFallbackOn(), new Metrics());
			actuator.markBridgeOnline(false, "test");
			check(name, actuator.supports().contains(Command.Kind.REFRESH_DEPOT_ROUTES), "RCON kinds remain available");
			check(name, !actuator.supports().contains(Command.Kind.SET_PLATFORM), "fine-grained kinds are unsupported");

			final Command command = new Command();
			command.kind = Command.Kind.REFRESH_DEPOT_ROUTES;
			command.args.put("depot", "Central Depot");
			final Actuator.Result result = actuator.execute(command);
			check(name, result.success(), "RCON delivery succeeded: " + result.detail());
			check(name, rconServer.commands().contains("mtr generatePath Central Depot"),
				"console command issued: " + rconServer.commands());

			final Command unsupported = new Command();
			unsupported.kind = Command.Kind.SET_PLATFORM;
			check(name, !actuator.supports().contains(unsupported.kind), "set_platform cannot be carried by RCON");
		} catch (Exception exception) {
			fail(name, exception);
		} finally {
			if (rconServer != null) {
				rconServer.close();
			}
		}
	}

	// ------------------------------------------------------------------ 2 real bridge wire format

	private void bridgeWireMapping() {
		final String name = "service speaks the bridge's wire format";
		MockTscServer mock = null;
		try {
			mock = new MockTscServer(0, true);
			mock.start();
			final Config config = Config.defaults();
			config.override("bridge.base_url", "http://127.0.0.1:" + mock.port());
			try (HttpJson http = new HttpJson(Duration.ofMillis(1500), 0, "selftest")) {
				final BridgeActuator actuator = new BridgeActuator(config, http,
					java.util.Set.of("set_platform", "deploy_train", "return_to_depot", "dwell_control"), 0);
				check(name, !actuator.supports().contains(Command.Kind.REROUTE), "reroute is not advertised by the bridge");

				final Command hold = new Command();
				hold.kind = Command.Kind.HOLD;
				hold.vehicleId = "1001";
				hold.args.put("seconds", 45);
				hold.idempotencyKey = "hold-test";
				final Actuator.Result holdResult = actuator.execute(hold);
				final Map<String, Object> holdWire = lastCommand(mock);
				check(name, "dwell_control".equals(holdWire.get("kind")), "hold maps to dwell_control: " + holdWire);
				check(name, String.valueOf(holdWire.get("args")).contains("45"), "seconds forwarded: " + holdWire.get("args"));
				check(name, holdResult.success(), "dwell delivery succeeded");

				final Command park = new Command();
				park.kind = Command.Kind.RETURN_TO_DEPOT;
				park.vehicleId = "1001";
				park.args.put("sidingId", "00000000000000aa");
				park.idempotencyKey = "park-test";
				actuator.execute(park);
				final Map<String, Object> parkWire = lastCommand(mock);
				check(name, "return_to_depot".equals(parkWire.get("kind")), "return maps to return_to_depot");
				check(name, String.valueOf(parkWire.get("args")).contains("00000000000000aa"), "sidingId forwarded");

				// 1001 was just parked by the previous command; use the still-running 1003.
				final Command move = new Command();
				move.kind = Command.Kind.SET_PLATFORM;
				move.vehicleId = "1003";
				move.args.put("platformId", Ids.hex(PLATFORM_AIRPORT_2));
				move.idempotencyKey = "move-running";
				final Actuator.Result deferred = actuator.execute(move);
				check(name, deferred.success() && !deferred.applied(), "running train is DEFERRED, not applied: " + deferred.detail());
			}
		} catch (Exception exception) {
			fail(name, exception);
		} finally {
			if (mock != null) {
				mock.close();
			}
		}
	}

	private static Map<String, Object> lastCommand(MockTscServer mock) {
		final List<Map<String, Object>> commands = mock.commands();
		return commands.isEmpty() ? Map.of() : commands.get(commands.size() - 1);
	}

	// ------------------------------------------------------------------ 4 dimensions

	private void dimensionIsolation() {
		final String name = "dimensions run isolated runtimes";
		MockTscServer mock = null;
		Service service = null;
		try {
			mock = new MockTscServer(0, true);
			mock.start();

			final Config config = Config.defaults();
			config.override("tsc.base_url", "http://127.0.0.1:" + mock.port());
			config.override("bridge.base_url", "http://127.0.0.1:" + mock.port());
			config.override("bridge.enabled", "false");
			config.override("admin.enabled", "false");
			config.override("tsc.poll_interval_ms", "400");
			config.override("network.adaptive", "false");
			config.override("state.dir", tempDirectory("dims").toString());
			config.override("log.level", "WARN");
			config.withDimensions(List.of(
				new org.tsc.dispatch.config.DimensionSpec("minecraft:overworld", 0, true),
				new org.tsc.dispatch.config.DimensionSpec("minecraft:the_nether", 1, true)));

			service = new Service(config);
			service.start();
			final long deadline = System.currentTimeMillis() + 6_000;
			while (System.currentTimeMillis() < deadline && service.runtimes().stream().anyMatch(runtime -> runtime.snapshot() == null)) {
				Thread.sleep(150);
			}
			check(name, service.runtimes().size() == 2, "two enabled dimensions produce two runtimes");
			check(name, service.runtimes().stream().allMatch(runtime -> runtime.snapshot() != null), "both received telemetry");
			check(name, service.health().get("dimensions") instanceof List<?> list && list.size() == 2, "health reports both");
			final DimensionRuntime first = service.runtimes().get(0);
			final DimensionRuntime second = service.runtimes().get(1);
			check(name, first.bus() != second.bus() && first.leases() != second.leases(), "state is not shared");
		} catch (Exception exception) {
			fail(name, exception);
		} finally {
			if (service != null) {
				service.close();
			}
			if (mock != null) {
				mock.close();
			}
		}
	}

	// ------------------------------------------------------------------ 5 scale

	private void adaptivePolling() {
		final String name = "tiers and clamps drive the poll interval";
		try {
			final Config small = Config.defaults();
			small.override("network.max_trains", "5");
			check(name, small.pollingInterval() == 1000, "5 trains -> 1000ms");

			final Config medium = Config.defaults();
			medium.override("network.max_trains", "20");
			check(name, medium.pollingInterval() == 2000, "20 trains -> 2000ms");

			final Config large = Config.defaults();
			large.override("network.max_trains", "500");
			check(name, large.pollingInterval() == 4000, "500 trains -> 4000ms");

			final Config clamped = Config.defaults();
			clamped.override("network.max_trains", "500");
			clamped.override("network.polling_max_ms", "1500");
			check(name, clamped.pollingInterval() == 1500, "clamped to polling_max_ms");

			final Config fixed = Config.defaults();
			fixed.override("network.adaptive", "false");
			fixed.override("tsc.poll_interval_ms", "700");
			check(name, fixed.pollingInterval() == 700, "adaptive off uses the baseline");
		} catch (Exception exception) {
			fail(name, exception);
		}
	}

	// ------------------------------------------------------------------ 1 port discovery

	private void portDiscovery() {
		final String name = "port discovery finds the live port";
		MockTscServer mock = null;
		try {
			mock = new MockTscServer(0, false);
			mock.start();
			final int deadPort = closedPort();

			final Config config = Config.defaults();
			config.override("tsc.base_url", "http://127.0.0.1:" + deadPort);
			config.override("tsc.port_discovery.enabled", "true");
			config.override("tsc.port_discovery.range_start", String.valueOf(mock.port()));
			config.override("tsc.port_discovery.range_end", String.valueOf(mock.port()));
			config.override("tsc.timeout_ms", "400");
			config.override("tsc.retry_count", "0");

			try (HttpJson http = new HttpJson(Duration.ofMillis(600), 0, "selftest")) {
				final PortDiscovery.Result result = PortDiscovery.resolve(config, http, 0);
				check(name, result.discovered(), "discovered=" + result.discovered() + " tried=" + result.tried());
				check(name, result.port() == mock.port(), "resolved port " + result.port() + " expected " + mock.port());
			}
		} catch (Exception exception) {
			fail(name, exception);
		} finally {
			if (mock != null) {
				mock.close();
			}
		}
	}

	// ------------------------------------------------------------------ helpers

	private static int closedPort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static Path tempDirectory(String name) throws Exception {
		final Path directory = Path.of("build", "selftest-tmp", name + "-" + System.nanoTime());
		Files.createDirectories(directory);
		return directory;
	}

	private Config config() {
		final Config config = Config.defaults();
		config.override("trigger.approach_lead_seconds", "600");
		config.override("trigger.platform_headway_seconds", "90");
		config.override("trigger.cooldown_seconds", "0");
		config.override("dispatch.dedup_window_seconds", "600");
		return config;
	}

	private Dispatcher dispatcher(Config config) {
		final LeaseTable leases = new LeaseTable();
		final CommandBus bus = new CommandBus(config, new StateStore(Path.of("build", "selftest-state")), new Metrics(), leases,
			new org.tsc.dispatch.actuator.DryRunActuator());
		bus.init();
		return new Dispatcher(config, new Metrics(), leases, bus);
	}

	private WorldSnapshot snapshot(List<Vehicle> vehicles) {
		final long now = System.currentTimeMillis();
		return new WorldSnapshot(now, now, "selftest", true, stations(), routes(), platforms(), depots(), vehicles);
	}

	private List<Station> stations() {
		return List.of(
			new Station(Ids.hex(STATION_CENTRAL), "Central", 0, List.of()),
			new Station(Ids.hex(STATION_NORTH), "North", 0, List.of()),
			new Station(Ids.hex(STATION_SOUTH), "South", 0, List.of()),
			new Station(Ids.hex(STATION_AIRPORT), "Airport", 0, List.of()));
	}

	private List<Platform> platforms() {
		return List.of(
			new Platform(PLATFORM_CENTRAL_1, Ids.hex(PLATFORM_CENTRAL_1), "Central 1", Ids.hex(STATION_CENTRAL), 20_000),
			new Platform(PLATFORM_CENTRAL_2, Ids.hex(PLATFORM_CENTRAL_2), "Central 2", Ids.hex(STATION_CENTRAL), 20_000),
			new Platform(PLATFORM_NORTH_1, Ids.hex(PLATFORM_NORTH_1), "North 1", Ids.hex(STATION_NORTH), 20_000),
			new Platform(PLATFORM_SOUTH_1, Ids.hex(PLATFORM_SOUTH_1), "South 1", Ids.hex(STATION_SOUTH), 20_000),
			new Platform(PLATFORM_AIRPORT_1, Ids.hex(PLATFORM_AIRPORT_1), "Airport 1", Ids.hex(STATION_AIRPORT), 20_000),
			new Platform(PLATFORM_AIRPORT_2, Ids.hex(PLATFORM_AIRPORT_2), "Airport 2", Ids.hex(STATION_AIRPORT), 20_000));
	}

	private List<Route> routes() {
		return List.of(
			new Route(Ids.hex(ROUTE_LOOP), "Airport Express", "A", 0x3366ff, "TRAIN", "CLOCKWISE",
				List.of(stop(STATION_CENTRAL, "Central", 0), stop(STATION_NORTH, "North", 200), stop(STATION_AIRPORT, "Airport", 400)),
				List.of(60_000L, 90_000L, 120_000L), List.of("Central Depot")),
			new Route(Ids.hex(ROUTE_SOUTH), "South Shuttle", "S", 0x33cc66, "TRAIN", "NONE",
				List.of(stop(STATION_CENTRAL, "Central", 0), stop(STATION_SOUTH, "South", 200), stop(STATION_AIRPORT, "Airport", 400)),
				List.of(70_000L, 100_000L), List.of("Central Depot")));
	}

	private static Route.Stop stop(long stationId, String name, int x) {
		return new Route.Stop(Ids.hex(stationId), name, x, 64, 0, 20_000);
	}

	private List<Depot> depots() {
		return List.of(new Depot(null, "Central Depot", List.of(Ids.hex(ROUTE_LOOP), Ids.hex(ROUTE_SOUTH)), List.of()));
	}

	private Vehicle vehicle(String id, long routeId, long platformId, long stationId, long eta, long departure, double passengers) {
		final Vehicle vehicle = new Vehicle();
		vehicle.id = id;
		vehicle.name = id;
		vehicle.origin = "selftest";
		vehicle.controllable = true;
		vehicle.routeHex = Ids.hex(routeId);
		vehicle.platformHex = Ids.hex(platformId);
		vehicle.stationHex = Ids.hex(stationId);
		vehicle.etaMillis = eta;
		vehicle.departureMillis = departure;
		vehicle.passengerCount = passengers;
		return vehicle;
	}

	private void check(String scenario, boolean condition, String message) {
		if (condition) {
			passed++;
			System.out.println("  ok    " + scenario + " :: " + message);
		} else {
			failed++;
			failures.add(scenario + " :: " + message);
			System.out.println("  FAIL  " + scenario + " :: " + message);
		}
	}

	private void fail(String scenario, Exception exception) {
		failed++;
		failures.add(scenario + " :: " + exception);
		System.out.println("  ERROR " + scenario + " :: " + exception);
		exception.printStackTrace(System.out);
	}
}
