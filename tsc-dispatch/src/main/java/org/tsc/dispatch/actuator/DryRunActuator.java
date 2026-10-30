package org.tsc.dispatch.actuator;

import org.tsc.dispatch.dispatch.Command;
import org.tsc.dispatch.util.Log;

import java.util.EnumSet;
import java.util.Set;

/**
 * Default actuator: records what would have been sent and reports success without touching
 * the game server. Safe to leave enabled while tuning weights.
 */
public final class DryRunActuator implements Actuator {

	private static final Set<Command.Kind> ALL = Set.copyOf(EnumSet.allOf(Command.Kind.class));

	@Override
	public String kind() {
		return "dry-run";
	}

	@Override
	public Set<Command.Kind> supports() {
		return ALL;
	}

	@Override
	public Result execute(Command command) {
		Log.info("dry-run command", "seq", command.seq, "kind", command.kind, "vehicle", command.vehicleId, "args", command.args);
		return Result.accepted("dry-run");
	}
}
