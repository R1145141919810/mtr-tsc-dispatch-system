package org.tsc.dispatch;

import org.tsc.dispatch.actuator.DryRunActuator;
import org.tsc.dispatch.config.Config;
import org.tsc.dispatch.dispatch.Command;
import org.tsc.dispatch.dispatch.CommandBus;
import org.tsc.dispatch.dispatch.Decision;
import org.tsc.dispatch.dispatch.Dispatcher;
import org.tsc.dispatch.dispatch.LeaseTable;
import org.tsc.dispatch.graph.GraphBuilder;
import org.tsc.dispatch.graph.RailGraph;
import org.tsc.dispatch.mock.MockTscServer;
import org.tsc.dispatch.model.Depot;
import org.tsc.dispatch.model.Platform;
import org.tsc.dispatch.model.Route;
import org.tsc.dispatch.model.Station;
import org.tsc.dispatch.model.Vehicle;
import org.tsc.dispatch.model.WorldSnapshot;
import org.tsc.dispatch.persist.StateStore;
import org.tsc.dispatch.plan.CostModel;
import org.tsc.dispatch.plan.Occupancy;
import org.tsc.dispatch.plan.PlanResult;
import org.tsc.dispatch.plan.Router;
import org.tsc.dispatch.util.Ids;
import org.tsc.dispatch.util.Metrics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic scenario tests runnable from the jar ({@code java -jar ... selftest}).
 *
 * <p>They cover the failure modes that matter in production: two trains racing for one
 * platform, a junction blocked ahead, scheduled depot returns with the passenger boundary,
 * duplicate suppression across a restart, and the capability degradation path when no write
 * channel exists. The last scenario drives the whole stack over real HTTP against the bundled
 * mock server.</p>
 */
public final class SelfTest {

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

	public boolean run() {
		System.out.println("tsc-dispatch selftest " + Config.VERSION);
		System.out.println("-------------------------------------------------------------");
		scenarioPlanner();
		scenarioPlatformConflict();
		scenarioJunctionReroute();
		scenarioTimedDepotReturn();
		scenarioRestartWithoutDuplicates();
		scenarioStockModeDegrades();
		scenarioEndToEndOverHttp();

		final ConfigScenarios.Result configScenarios = ConfigScenarios.run();
		passed += configScenarios.passed();
		failed += configScenarios.failed();
		failures.addAll(configScenarios.failures());
		System.out.println("-------------------------------------------------------------");
		System.out.println(passed + " passed, " + failed + " failed");
		failures.forEach(failure -> System.out.println("  FAIL " + failure));
		return failed == 0;
	}

	// ------------------------------------------------------------------ scenarios

	private void scenarioPlanner() {
		final String name = "planner prefers the cheaper of two routes";
		try {
			final WorldSnapshot snapshot = snapshot(List.of());
			final RailGraph graph = GraphBuilder.build(snapshot, config());
			final Router router = new Router(graph, new CostModel(config()));
			final PlanResult plan = router.plan(RailGraph.stationNode(Ids.hex(STATION_CENTRAL)),
				RailGraph.stationNode(Ids.hex(STATION_AIRPORT)), snapshot.serverTimeMillis, context(snapshot, ""));
			check(name, plan.reachedTarget(), "target reachable");
			check(name, plan.nodes().contains(RailGraph.stationNode(Ids.hex(STATION_NORTH))),
				"northern alignment chosen " + plan.nodes());
			check(name, plan.travelMillis() > 0, "travel time positive");
		} catch (Exception exception) {
			fail(name, exception);
		}
	}

	private void scenarioPlatformConflict() {
		final String name = "later train yields to the earlier one at a shared station";
		try {
			final long now = System.currentTimeMillis();
			final Vehicle arriving = vehicle("v1", ROUTE_LOOP, PLATFORM_AIRPORT_1, STATION_AIRPORT, now + 40_000, now + 75_000, 30);
			final Vehicle parked = vehicle("v2", ROUTE_SOUTH, PLATFORM_AIRPORT_1, STATION_AIRPORT, now + 30_000, now + 50_000, 10);
			parked.inDepot = true;

			final List<Decision> parkedDecisions = dispatcher(config()).evaluate(snapshot(List.of(arriving, parked)));
			final Decision platform = parkedDecisions.stream()
				.filter(decision -> "PLATFORM_SELECTION".equals(decision.type))
				.findFirst().orElse(null);
			check(name, platform != null, "parked train can be re-platformed " + parkedDecisions);
			if (platform != null) {
				check(name, "v2".equals(platform.vehicleId), "yielding vehicle " + platform.vehicleId);
				check(name, platform.command.kind == Command.Kind.SET_PLATFORM, "command kind " + platform.command.kind);
				check(name, Ids.hex(PLATFORM_AIRPORT_2).equals(platform.command.args.get("platformId")),
					"alternative platform " + platform.command.args.get("platformId"));
			}

			// A running train cannot be re-platformed; the only honest lever is holding its dwell.
			final Vehicle running = vehicle("v3", ROUTE_SOUTH, PLATFORM_AIRPORT_1, STATION_AIRPORT, now + 45_000, now + 80_000, 10);
			final List<Decision> runningDecisions = dispatcher(config()).evaluate(snapshot(List.of(arriving, running)));
			final Decision hold = runningDecisions.stream()
				.filter(decision -> "HOLD_FOR_PLATFORM".equals(decision.type))
				.findFirst().orElse(null);
			check(name, hold != null, "running train is held instead " + runningDecisions);
			if (hold != null) {
				check(name, hold.command.kind == Command.Kind.HOLD, "hold command kind " + hold.command.kind);
				check(name, ((Number) hold.command.args.get("seconds")).longValue() > 0, "hold seconds positive");
			}
		} catch (Exception exception) {
			fail(name, exception);
		}
	}

	private void scenarioJunctionReroute() {
		final String name = "approaching train is rerouted around a blocked junction";
		try {
			final long now = System.currentTimeMillis();
			final Vehicle approach = vehicle("v9", ROUTE_LOOP, PLATFORM_CENTRAL_1, STATION_CENTRAL, now + 20_000, now + 45_000, 5);
			final Vehicle blocker = vehicle("b1", ROUTE_LOOP, PLATFORM_NORTH_1, STATION_NORTH, now - 10_000, now + 300_000, 0);
			final WorldSnapshot snapshot = snapshot(List.of(approach, blocker));
			final Dispatcher dispatcher = dispatcher(config());
			final List<Decision> decisions = dispatcher.evaluate(snapshot);
			final Decision reroute = decisions.stream()
				.filter(decision -> "REROUTE".equals(decision.type))
				.findFirst().orElse(null);
			check(name, reroute != null, "reroute decision produced " + decisions);
			if (reroute != null) {
				check(name, Ids.hex(STATION_NORTH).equals(reroute.command.args.get("avoidStationId")),
					"avoids North: " + reroute.command.args.get("avoidStationId"));
				check(name, Double.parseDouble(String.valueOf(reroute.detail.get("costAlternative")))
					< Double.parseDouble(String.valueOf(reroute.detail.get("costStay"))), "alternative cheaper than staying");
			}
		} catch (Exception exception) {
			fail(name, exception);
		}
	}

	private void scenarioTimedDepotReturn() {
		final String name = "service budget triggers a depot return, passengers defer it";
		try {
			final long now = System.currentTimeMillis();
			final Config config = config();
			config.override("trigger.return-after-seconds", "60");
			final Vehicle running = vehicle("v5", ROUTE_LOOP, PLATFORM_CENTRAL_1, STATION_CENTRAL, now + 10_000, now + 30_000, 0);
			running.serviceStartMillis = now - 7_200_000L;
			final Dispatcher dispatcher = dispatcher(config);
			final List<Decision> decisions = dispatcher.evaluate(snapshot(List.of(running)));
			final Decision returned = decisions.stream()
				.filter(decision -> "RETURN_TO_DEPOT".equals(decision.type))
				.findFirst().orElse(null);
			check(name, returned != null, "depot return decision produced");
			if (returned != null) {
				check(name, returned.command != null && returned.command.kind == Command.Kind.RETURN_TO_DEPOT,
					"command kind " + (returned.command == null ? "none" : returned.command.kind));
				check(name, "Central Depot".equals(String.valueOf(returned.command.args.get("depot"))),
					"target depot " + returned.command.args.get("depot"));
			}

			final Vehicle dwelling = vehicle("v6", ROUTE_LOOP, PLATFORM_CENTRAL_1, STATION_CENTRAL, now - 5_000, now + 25_000, 25);
			dwelling.atPlatform = true;
			dwelling.serviceStartMillis = now - 7_200_000L;
			final List<Decision> deferred = dispatcher(config()).evaluate(snapshot(List.of(dwelling)));
			final Decision hold = deferred.stream()
				.filter(decision -> "RETURN_TO_DEPOT".equals(decision.type))
				.findFirst().orElse(null);
			check(name, hold != null && hold.command == null, "passenger carrying train deferred");
			check(name, hold != null && !hold.executable, "deferred decision not executable");
		} catch (Exception exception) {
			fail(name, exception);
		}
	}

	private void scenarioRestartWithoutDuplicates() {
		final String name = "command deduplication survives a restart";
		try {
			final Path directory = tempDirectory("dedup");
			final Config config = config();
			final long now = System.currentTimeMillis();
			final WorldSnapshot snapshot = snapshot(List.of(
				vehicle("v1", ROUTE_LOOP, PLATFORM_AIRPORT_1, STATION_AIRPORT, now + 40_000, now + 75_000, 3),
				vehicle("v2", ROUTE_SOUTH, PLATFORM_AIRPORT_1, STATION_AIRPORT, now + 48_000, now + 80_000, 3)));

			final CommandBus first = new CommandBus(config, new StateStore(directory), new Metrics(), new LeaseTable(), new DryRunActuator());
			first.init();
			final Command command = platformCommand("v2", Ids.hex(PLATFORM_AIRPORT_2), Ids.hex(PLATFORM_AIRPORT_1), now);
			final Decision delivered = new Decision(now, "PLATFORM_SELECTION", "v2", "test", true, command);
			first.submit(delivered, snapshot);
			check(name, delivered.status == Decision.Status.ACCEPTED, "first delivery " + delivered.status);

			final Decision again = new Decision(now, "PLATFORM_SELECTION", "v2", "test", true, command);
			first.submit(again, snapshot);
			check(name, again.status == Decision.Status.SKIPPED, "duplicate " + again.status);

			final CommandBus restarted = new CommandBus(config, new StateStore(directory), new Metrics(), new LeaseTable(), new DryRunActuator());
			restarted.init();
			final Command replayed = platformCommand("v2", Ids.hex(PLATFORM_AIRPORT_2), Ids.hex(PLATFORM_AIRPORT_1), now);
			replayed.idempotencyKey = command.idempotencyKey;
			final Decision afterRestart = new Decision(now, "PLATFORM_SELECTION", "v2", "test", true, replayed);
			restarted.submit(afterRestart, snapshot);
			check(name, afterRestart.status == Decision.Status.SKIPPED, "after restart " + afterRestart.status);

			final Command stale = platformCommand("v2", Ids.hex(PLATFORM_AIRPORT_2), Ids.hex(PLATFORM_AIRPORT_1), now);
			stale.idempotencyKey = "stale-test";
			stale.preconditionPlatformHex = Ids.hex(PLATFORM_SOUTH_1);
			final Decision staleDecision = new Decision(now, "PLATFORM_SELECTION", "v2", "test", true, stale);
			restarted.submit(staleDecision, snapshot);
			check(name, staleDecision.status == Decision.Status.SKIPPED, "stale precondition " + staleDecision.status);
		} catch (Exception exception) {
			fail(name, exception);
		}
	}

	private void scenarioStockModeDegrades() {
		final String name = "stock telemetry degrades decisions to advisories";
		try {
			final long now = System.currentTimeMillis();
			final List<Vehicle> vehicles = new ArrayList<>();
			final Vehicle first = vehicle("slot-a", ROUTE_LOOP, PLATFORM_AIRPORT_1, STATION_AIRPORT, now + 30_000, now + 60_000, 4);
			final Vehicle second = vehicle("slot-b", ROUTE_SOUTH, PLATFORM_AIRPORT_1, STATION_AIRPORT, now + 35_000, now + 60_000, 4);
			first.controllable = false;
			second.controllable = false;
			first.origin = "tsc-stock-http";
			second.origin = "tsc-stock-http";
			vehicles.add(first);
			vehicles.add(second);
			final WorldSnapshot snapshot = new WorldSnapshot(now, now, "tsc-stock-http", false,
				stations(), routes(), platforms(), depots(), vehicles);
			final Dispatcher dispatcher = dispatcher(config());
			final List<Decision> decisions = dispatcher.tick(snapshot);
			check(name, !decisions.isEmpty(), "advisory decisions produced");
			check(name, decisions.stream().allMatch(decision -> decision.status == Decision.Status.ADVISORY),
				"all decisions advisory: " + decisions);
		} catch (Exception exception) {
			fail(name, exception);
		}
	}

	private void scenarioEndToEndOverHttp() {
		final String name = "end to end against the mock webserver";
		MockTscServer mock = null;
		Service service = null;
		try {
			final Path stateDirectory = tempDirectory("e2e");
			mock = new MockTscServer(0, true);
			mock.start();
			final Config config = config();
			config.override("bridge.base-url", "http://127.0.0.1:" + mock.port());
			config.override("tsc.base-url", "http://127.0.0.1:" + mock.port());
			config.override("admin.enabled", "false");
			config.override("tsc.poll-interval-ms", "400");
			config.override("state.dir", stateDirectory.toString());
			config.override("log.level", "WARN");

			service = new Service(config);
			service.start();
			final long deadline = System.currentTimeMillis() + 8_000;
			while (System.currentTimeMillis() < deadline && mock.appliedCommands() == 0) {
				Thread.sleep(150);
			}
			check(name, service.snapshot() != null, "snapshot received");
			check(name, service.snapshot() != null && "tsc-control-bridge".equals(service.snapshot().source),
				"telemetry source " + (service.snapshot() == null ? "none" : service.snapshot().source));
			check(name, mock.appliedCommands() >= 1, "command reached the mock server");

			final long appliedAfterFirst = mock.appliedCommands();
			Thread.sleep(1_500);
			check(name, mock.appliedCommands() == appliedAfterFirst,
				"no duplicate command (extra=" + (mock.appliedCommands() - appliedAfterFirst) + ")");
			check(name, mock.vehicle("1002") != null && mock.vehicle("1002").platformId == PLATFORM_AIRPORT_2,
				"later train moved to Airport 2");
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

	// ------------------------------------------------------------------ fixtures

	private static Path tempDirectory(String name) {
		try {
			final Path directory = Path.of("build", "selftest-tmp", name + "-" + System.nanoTime());
			Files.createDirectories(directory);
			return directory;
		} catch (Exception exception) {
			throw new RuntimeException("cannot create test directory: " + exception.getMessage(), exception);
		}
	}

	private Config config() {
		final Config config = Config.defaults();
		config.override("trigger.approach-lead-seconds", "600");
		config.override("trigger.platform-headway-seconds", "90");
		config.override("trigger.cooldown-seconds", "0");
		config.override("dispatch.dedup-window-seconds", "600");
		return config;
	}

	private Dispatcher dispatcher(Config config) {
		final LeaseTable leases = new LeaseTable();
		final CommandBus bus = new CommandBus(config, new StateStore(Path.of("build", "selftest-state")), new Metrics(), leases, new DryRunActuator());
		bus.init();
		return new Dispatcher(config, new Metrics(), leases, bus);
	}

	private WorldSnapshot snapshot(List<Vehicle> vehicles) {
		final long now = vehicles.isEmpty() ? System.currentTimeMillis() : vehicles.get(0).etaMillis - 30_000;
		final long stamp = Math.max(now, System.currentTimeMillis() - 30_000);
		return new WorldSnapshot(stamp, System.currentTimeMillis(), "selftest", true,
			stations(), routes(), platforms(), depots(), vehicles);
	}

	private Router.PlanContext context(WorldSnapshot snapshot, String vehicleId) {
		final Map<String, Integer> counts = new LinkedHashMap<>();
		snapshot.platforms.values().forEach(platform -> counts.merge(platform.stationHex(), 1, Integer::sum));
		return new Router.PlanContext(vehicleId, Occupancy.from(snapshot), counts, Set.of(), 0);
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

	private Command platformCommand(String vehicleId, String platformId, String previousPlatformId, long now) {
		final Command command = new Command();
		command.kind = Command.Kind.SET_PLATFORM;
		command.vehicleId = vehicleId;
		command.args.put("stationId", Ids.hex(STATION_AIRPORT));
		command.args.put("platformId", platformId);
		command.args.put("previousPlatformId", previousPlatformId);
		command.preconditionVehicleId = vehicleId;
		command.preconditionPlatformHex = previousPlatformId;
		command.preconditionObservedAt = now;
		command.expiresAtMillis = now + 60_000;
		command.idempotencyKey = "platform|" + vehicleId + "|" + platformId + "|" + (now / 60_000);
		return command;
	}

	// ------------------------------------------------------------------ assertions

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
