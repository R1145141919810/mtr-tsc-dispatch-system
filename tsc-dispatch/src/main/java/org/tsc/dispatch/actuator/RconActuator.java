package org.tsc.dispatch.actuator;

import org.tsc.dispatch.config.Config;
import org.tsc.dispatch.dispatch.Command;
import org.tsc.dispatch.util.Log;

import java.util.EnumSet;
import java.util.Set;

/**
 * Coarse write channel built on vanilla RCON.
 *
 * <p>MTR registers a small {@code /mtr} command tree (generate depot paths, instant deploy,
 * clear vehicles). RCON can invoke it without any mod being installed, which makes this the
 * only stock-compatible way to nudge the simulation. Everything finer grained needs the
 * control bridge.</p>
 */
public final class RconActuator implements Actuator {

	private static final Set<Command.Kind> SUPPORTED = Set.copyOf(EnumSet.of(
		Command.Kind.REFRESH_DEPOT_ROUTES, Command.Kind.INSTANT_DEPLOY, Command.Kind.CLEAR_DEPOT));

	private final Config config;

	public RconActuator(Config config) {
		this.config = config;
	}

	public boolean configured() {
		return !config.get("rcon.password").isEmpty();
	}

	@Override
	public String kind() {
		return "rcon";
	}

	@Override
	public Set<Command.Kind> supports() {
		return SUPPORTED;
	}

	@Override
	public Result execute(Command command) {
		final String depot = String.valueOf(command.args.getOrDefault("depot", ""));
		if (depot.isBlank()) {
			return Result.fatal("missing depot name");
		}
		final String consoleCommand = switch (command.kind) {
			case REFRESH_DEPOT_ROUTES -> "mtr generatePath " + depot;
			case INSTANT_DEPLOY -> "mtr instantDeploy " + depot;
			case CLEAR_DEPOT -> "mtr clearVehicles " + depot;
			default -> null;
		};
		if (consoleCommand == null) {
			return Result.fatal("unsupported command " + command.kind);
		}
		try (RconClient client = new RconClient(config.get("rcon.host"), config.getInt("rcon.port"),
			config.get("rcon.password"), config.getInt("rcon.timeout-ms"))) {
			final String response = client.command(consoleCommand);
			Log.debug("rcon command sent", "command", consoleCommand, "response", response);
			final String lowered = response.toLowerCase(java.util.Locale.ROOT);
			if (lowered.contains("unknown") || lowered.contains("error") || lowered.contains("incorrect")) {
				return Result.fatal("rcon rejected: " + response);
			}
			return Result.accepted("rcon:" + consoleCommand);
		} catch (Exception exception) {
			return Result.retryable("rcon failure: " + exception.getMessage());
		}
	}
}
