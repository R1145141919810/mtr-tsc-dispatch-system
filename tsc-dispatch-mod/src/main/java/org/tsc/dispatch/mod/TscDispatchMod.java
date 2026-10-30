package org.tsc.dispatch.mod;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.tsc.dispatch.Service;
import org.tsc.dispatch.config.Config;
import org.tsc.dispatch.config.ConfigTemplate;
import org.tsc.dispatch.util.Json;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.Map;

/**
 * Loads the dispatch service as an ordinary Forge mod.
 *
 * <p>The service is a plain-Java component: it reads telemetry over HTTP and writes commands
 * through the control bridge, so nothing about it needs to be a mod. Shipping it as one anyway
 * fixes the practical problem that a bare jar in {@code mods/} is invisible to Forge (no
 * {@code mods.toml}, no {@code @Mod}) and therefore never loads; this entry point starts the
 * service with the integrated or dedicated server and stops it when the world closes.</p>
 *
 * <p>Two failure modes are handled explicitly instead of crashing the game: a configuration
 * that the service rejects (logged with the reason) and a second instance already answering on
 * the dashboard port (the mod then stays idle instead of fighting for the port).</p>
 */
@Mod(TscDispatchMod.MOD_ID)
public final class TscDispatchMod {

	public static final String MOD_ID = "tscdispatch";
	private static final Logger LOGGER = LogManager.getLogger("tsc-dispatch");
	private static final String CONFIG_FILE = "tsc-dispatch.yml";

	/**
	 * Start is deliberately delayed: the control bridge (and the telemetry web server) bind
	 * during their own server-started handlers, which may run after this one. Starting the
	 * service immediately made its very first bridge probe miss and report "observe" for a
	 * whole probe interval.
	 */
	private static final long START_DELAY_MILLIS = 1500L;
	/**
	 * A restart can leave the dashboard port briefly occupied: the previous JVM may still be
	 * exiting, and its connections sit in TIME_WAIT. Treating that as "another dispatcher owns
	 * the port" idled the whole service for a whole session, so retry for a minute first.
	 */
	private static final long PORT_RETRY_MILLIS = 5_000L;
	private static final int PORT_RETRIES = 12;

	private final ScheduledExecutorService starter = Executors.newSingleThreadScheduledExecutor(runnable -> {
		final Thread thread = new Thread(runnable, "tsc-dispatch-start");
		thread.setDaemon(true);
		return thread;
	});

	private volatile boolean stopping;
	private int startAttempts;
	private ScheduledFuture<?> pendingStart;
	private Service service;
	private String state = "idle";

	public TscDispatchMod() {
		MinecraftForge.EVENT_BUS.register(this);
		LOGGER.info("loaded; the dispatch service starts with the (integrated) server");
	}

	/**
	 * {@code /tscdispatch} answers the one question a mod jar cannot answer by existing:
	 * "is the service actually running in this game?". The service is not a mod, so it never
	 * shows up in the mod list - this command is where its state is observable.
	 */
	@SubscribeEvent
	public void onRegisterCommands(RegisterCommandsEvent event) {
		final CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
		dispatcher.register(Commands.literal("tscdispatch")
			.executes(context -> report(context.getSource())));
	}

	private int report(CommandSourceStack source) {
		final String text = "tsc-dispatch: " + state
			+ (service == null ? "" : " | dashboard http://127.0.0.1:8787/")
			+ " | config config/tsc-dispatch.yml";
		source.sendSuccess(() -> Component.literal(text), false);
		return service != null ? 1 : 0;
	}

	@SubscribeEvent
	public void onServerStarted(ServerStartedEvent event) {
		stopping = false;
		pendingStart = starter.schedule(TscDispatchMod.this::start, START_DELAY_MILLIS, TimeUnit.MILLISECONDS);
	}

	@SubscribeEvent
	public void onServerStopping(ServerStoppingEvent event) {
		stop();
	}

	private synchronized void start() {
		if (service != null || stopping) {
			return;
		}
		if (Boolean.getBoolean("tscdispatch.disabled") || Files.exists(disabledMarker())) {
			state = "disabled";
			LOGGER.warn("disabled by system property or {}; service not started", disabledMarker());
			return;
		}
		try {
			final Path directory = FMLPaths.CONFIGDIR.get();
			final Path file = directory.resolve(CONFIG_FILE);
			if (Files.notExists(file)) {
				Files.createDirectories(directory);
				Files.writeString(file, ConfigTemplate.YAML, StandardCharsets.UTF_8);
				LOGGER.info("wrote the default configuration to {}", file);
			}
			final Config config = Config.load(file);
			alignBridgeToken(config, directory);
			// Keep the service's runtime state next to its configuration instead of the game root.
			config.override("state.dir", directory.resolve("tsc-dispatch-state").toString());

			final String host = config.get("admin.host", "127.0.0.1");
			final int port = config.getInt("admin.port");
			if (config.getBool("admin.enabled") && !portFree(host, port)) {
				startAttempts++;
				if (startAttempts <= PORT_RETRIES) {
					LOGGER.info("{}:{} is busy (attempt {}/{}); retrying in {}s - a previous instance may "
							+ "still be shutting down", host, port, startAttempts, PORT_RETRIES,
						PORT_RETRY_MILLIS / 1000L);
					pendingStart = starter.schedule(this::start, PORT_RETRY_MILLIS, TimeUnit.MILLISECONDS);
				} else {
					// A live listener is a real conflict (an external process, or another mod's own
					// integration). Starting anyway would half-initialise this one and log a bind error.
					state = "standby";
					LOGGER.warn("{}:{} stayed busy for {}s; this instance stays idle (another dispatcher owns it)",
						host, port, (PORT_RETRIES * PORT_RETRY_MILLIS) / 1000L);
				}
				return;
			}

			final Service started = new Service(config);
			started.start();
			service = started;
			state = "running";
			LOGGER.info("running in-process (dashboard http://{}:{}/, config {})", host, port, file);
			if (isLoopback(host)) {
				// The usual "the dashboard answers with no data" report: the service is fine, but it
				// listens on loopback while the game runs inside a container (or the operator expects
				// LAN access). Say it out loud instead of leaving them to guess.
				LOGGER.info("dashboard is loopback-only ({}:{}); from another machine or through a "
					+ "published container port it is unreachable - set admin.host: 0.0.0.0 in config/{} "
					+ "to expose it", host, port, CONFIG_FILE);
			}
		} catch (Throwable throwable) {
			state = "failed";
			LOGGER.error("failed to start: {} (the game keeps running; fix config/{} and restart)",
				throwable.toString(), CONFIG_FILE);
		}
	}

	private synchronized void stop() {
		stopping = true;
		if (pendingStart != null) {
			pendingStart.cancel(false);
			pendingStart = null;
		}
		if (service == null) {
			return;
		}
		try {
			service.close();
			LOGGER.info("stopped with the server");
		} catch (Throwable throwable) {
			LOGGER.warn("stop reported {}", throwable.toString());
		} finally {
			service = null;
			state = "stopped";
		}
	}

	/**
	 * The control bridge is usually a sibling mod in the same instance, and it enforces a bearer
	 * token. When the service configuration leaves {@code bridge.auth_token} empty we adopt the
	 * token from the bridge's own {@code config/mtbridge.json} - otherwise every probe answers
	 * 401 and the service silently degrades to read-only telemetry.
	 */
	private static void alignBridgeToken(Config config, Path directory) {
		if (!config.get("bridge.auth_token").isEmpty()) {
			return;
		}
		try {
			final Path bridgeConfig = directory.resolve("mtbridge.json");
			if (Files.notExists(bridgeConfig)) {
				return;
			}
			final Object parsed = Json.parse(Files.readString(bridgeConfig, StandardCharsets.UTF_8));
			if (!(parsed instanceof Map<?, ?> map)) {
				return;
			}
			@SuppressWarnings("unchecked")
			final String token = Json.string((Map<String, Object>) map, "token", "");
			if (!token.isEmpty()) {
				config.override("bridge.auth_token", token);
				LOGGER.info("adopted the control bridge token from {}", bridgeConfig);
			}
		} catch (Throwable throwable) {
			LOGGER.warn("could not read the bridge configuration: {}", throwable.toString());
		}
	}

	private static Path disabledMarker() {
		return FMLPaths.CONFIGDIR.get().resolve("tsc-dispatch.disabled");
	}

	private static boolean isLoopback(String host) {
		return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
	}

	/** True when nothing is listening yet; a failed bind means somebody else owns the port. */
	private static boolean portFree(String host, int port) {
		try (ServerSocket socket = new ServerSocket()) {
			// Lingering TIME_WAIT connections from the previous run must not look like a live
			// listener; the real server binds with SO_REUSEADDR too.
			socket.setReuseAddress(true);
			socket.bind(new InetSocketAddress(host, port));
			return true;
		} catch (IOException exception) {
			return false;
		}
	}

	public String state() {
		return state;
	}
}
