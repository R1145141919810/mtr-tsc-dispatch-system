package org.tsc.dispatch;

import org.tsc.dispatch.config.Config;
import org.tsc.dispatch.config.ConfigValidator;
import org.tsc.dispatch.graph.GraphBuilder;
import org.tsc.dispatch.graph.RailGraph;
import org.tsc.dispatch.mock.MockTscServer;
import org.tsc.dispatch.model.WorldSnapshot;
import org.tsc.dispatch.plan.CostModel;
import org.tsc.dispatch.plan.Occupancy;
import org.tsc.dispatch.plan.Router;
import org.tsc.dispatch.source.BridgeSource;
import org.tsc.dispatch.source.HttpJson;
import org.tsc.dispatch.source.PortDiscovery;
import org.tsc.dispatch.source.StockTscSource;
import org.tsc.dispatch.source.TelemetrySource;
import org.tsc.dispatch.util.Ids;
import org.tsc.dispatch.util.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point and command line.
 *
 * <pre>
 *   run       start the dispatch service (default)
 *   validate  load and validate the configuration, exit non-zero on errors
 *   mock      start the bundled stand-in TSC webserver for local testing
 *   selftest  run the deterministic test scenarios
 *   plan      one-shot path query against a live or mock server
 * </pre>
 */
public final class Main {

	private Main() {
	}

	public static void main(String[] args) throws Exception {
		final String command = args.length == 0 ? "run" : args[0];
		final Map<String, String> options = parse(args, 1);
		switch (command) {
			case "run" -> runService(options);
			case "validate" -> validate(options);
			case "mock" -> runMock(options);
			case "selftest" -> {
				final boolean ok = new SelfTest().run();
				System.exit(ok ? 0 : 1);
			}
			case "plan" -> runPlan(options);
			case "version", "--version", "-v" -> System.out.println("tsc-dispatch " + Config.VERSION);
			case "help", "--help", "-h" -> printHelp();
			default -> {
				System.err.println("unknown command: " + command);
				printHelp();
				System.exit(2);
			}
		}
	}

	private static void runService(Map<String, String> options) throws Exception {
		final Config config = loadConfig(options);
		final Service service = new Service(config);
		try {
			service.start();
		} catch (IllegalStateException exception) {
			System.err.println("refusing to start: " + exception.getMessage());
			System.err.println("run 'java -jar tsc-dispatch.jar validate' for the full report");
			System.exit(2);
			return;
		}
		Runtime.getRuntime().addShutdownHook(new Thread(service::close, "shutdown"));
		System.out.println();
		System.out.println("tsc-dispatch " + Config.VERSION + " is running");
		System.out.println("  base url  : " + service.health().get("baseUrl"));
		System.out.println("  dimensions: " + service.runtimes().size());
		System.out.println("  actuator  : " + service.health().get("actuator"));
		System.out.println("  dashboard : http://" + config.get("admin.host") + ":" + config.getInt("admin.port") + "/");
		System.out.println("  press Ctrl+C to stop");
		System.out.println();
		new CountDownLatch(1).await();
	}

	private static void validate(Map<String, String> options) throws Exception {
		final Config config = loadConfig(options);
		final Map<String, Object> report = ConfigValidator.report(ConfigValidator.validate(config));
		System.out.println(Json.writePretty(report));
		System.exit(Boolean.TRUE.equals(report.get("ok")) ? 0 : 2);
	}

	private static void runMock(Map<String, String> options) throws Exception {
		final int port = Integer.parseInt(options.getOrDefault("port", "18888"));
		final boolean bridge = !options.containsKey("bridge") || Boolean.parseBoolean(options.get("bridge"));
		final MockTscServer mock = new MockTscServer(port, bridge);
		mock.start();
		System.out.println();
		System.out.println("mock TSC webserver listening on http://127.0.0.1:" + mock.port());
		System.out.println("  GET  /mtr/api/map/stations-and-routes");
		System.out.println("  POST /mtr/api/map/arrivals");
		System.out.println("  GET  /mtr/api/map/departures");
		if (bridge) {
			System.out.println("  GET  /dispatch/v1/capabilities");
			System.out.println("  GET  /dispatch/v1/state");
			System.out.println("  POST /dispatch/v1/command");
		}
		System.out.println();
		Runtime.getRuntime().addShutdownHook(new Thread(mock::close, "mock-shutdown"));
		new CountDownLatch(1).await();
	}

	private static void runPlan(Map<String, String> options) throws Exception {
		final Config config = loadConfig(options);
		try (HttpJson http = new HttpJson(Duration.ofMillis(config.getLong("tsc.timeout_ms")), 1, "tsc-dispatch/" + Config.VERSION)) {
			final PortDiscovery.Result port = PortDiscovery.resolve(config, http, 0);
			TelemetrySource source = new StockTscSource(config, http, 0, port.baseUrl());
			final BridgeSource bridge = new BridgeSource(config, http, 0);
			if (config.bridgeEnabled() && bridge.probe()) {
				source = bridge;
			}
			final WorldSnapshot snapshot = source.fetch();
			final RailGraph graph = GraphBuilder.build(snapshot, config);
			final Router router = new Router(graph, new CostModel(config, snapshot));
			final Map<String, Integer> counts = new LinkedHashMap<>();
			snapshot.platforms.values().forEach(platform -> counts.merge(platform.stationHex(), 1, Integer::sum));
			final Router.PlanContext context = new Router.PlanContext("", Occupancy.from(snapshot), counts, java.util.Set.of(), 0);
			final String from = RailGraph.stationNode(Ids.normalize(options.getOrDefault("from", "0")));
			final Map<String, Object> result = new LinkedHashMap<>();
			result.put("source", snapshot.source);
			result.put("port", port.toJson());
			result.put("graph", graph.describe());
			if (options.containsKey("depot")) {
				result.put("plan", router.planToNearestDepot(from, snapshot.serverTimeMillis, context).toJson());
			} else {
				final String to = RailGraph.stationNode(Ids.normalize(options.getOrDefault("to", "0")));
				result.put("plan", router.plan(from, to, snapshot.serverTimeMillis, context).toJson());
			}
			System.out.println(Json.writePretty(result));
		}
	}

	private static Config loadConfig(Map<String, String> options) throws Exception {
		final Path path = Path.of(options.getOrDefault("config", "config/dispatch.yml"));
		if (!Files.exists(path)) {
			Config.writeTemplate(path);
			System.out.println("wrote default configuration to " + path.toAbsolutePath());
		}
		final Config config = Config.load(path);
		for (final String setting : options.getOrDefault("set", "").split(",")) {
			final int equals = setting.indexOf('=');
			if (equals > 0) {
				final String key = setting.substring(0, equals).trim();
				final String value = setting.substring(equals + 1).trim();
				if (!config.override(key, value)) {
					System.err.println("unknown configuration key: " + key);
				}
			}
		}
		return config;
	}

	private static Map<String, String> parse(String[] args, int from) {
		final Map<String, String> options = new LinkedHashMap<>();
		for (int i = from; i < args.length; i++) {
			final String argument = args[i];
			if (!argument.startsWith("--")) {
				continue;
			}
			final String key = argument.substring(2);
			if (key.startsWith("no-")) {
				options.put(key.substring(3), "false");
				continue;
			}
			if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
				options.put(key, args[++i]);
			} else {
				options.put(key, "true");
			}
		}
		return options;
	}

	private static void printHelp() {
		System.out.println("""
			tsc-dispatch - external dynamic dispatch service for Minecraft Transit Railway / TSC

			usage: java -jar tsc-dispatch.jar <command> [options]

			commands
			  run        start the service (default when no command is given)
			  validate   load config/dispatch.yml and print the validation report
			  mock       start the bundled mock TSC webserver on --port (default 18888)
			  selftest   run the deterministic scenario tests and exit non-zero on failure
			  plan       one-shot path query: --from <stationHex> --to <stationHex> | --depot
			  version    print the version
			  help       this text

			options (all commands)
			  --config <path>   configuration file (default config/dispatch.yml)
			  --set k=v,k2=v2   override configuration values for this run
			""");
	}
}
