package org.tsc.dispatch.actuator;

import org.tsc.dispatch.dispatch.Command;

import java.util.Set;

/**
 * A write channel. Actuators are the only component allowed to talk to the game server's
 * mutable surface, and each one advertises exactly which command kinds it can honour so the
 * dispatcher can degrade per capability instead of failing wholesale.
 */
public interface Actuator extends AutoCloseable {

	record Result(boolean success, boolean applied, boolean retryable, String detail) {

		public static Result applied(String detail) {
			return new Result(true, true, false, detail);
		}

		public static Result accepted(String detail) {
			return new Result(true, false, false, detail);
		}

		public static Result retryable(String detail) {
			return new Result(false, false, true, detail);
		}

		public static Result fatal(String detail) {
			return new Result(false, false, false, detail);
		}
	}

	String kind();

	Set<Command.Kind> supports();

	Result execute(Command command);

	@Override
	default void close() {
	}
}
