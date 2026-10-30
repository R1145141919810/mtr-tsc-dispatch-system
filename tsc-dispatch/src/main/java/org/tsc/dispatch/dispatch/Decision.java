package org.tsc.dispatch.dispatch;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A dispatch decision plus its outcome. Decisions are produced first and executed second, so
 * the same object is used for dry-run analysis, for the admin API and for the execution log.
 */
public final class Decision {

	public enum Status {
		/** No write channel could carry this decision; it is reported only. */
		ADVISORY,
		/** A command was submitted and the actuator accepted it. */
		APPLIED,
		/** The actuator accepted the command but the effect is not confirmed yet. */
		ACCEPTED,
		/** Dropped before delivery (duplicate, expired, precondition mismatch). */
		SKIPPED,
		/** Delivery failed after every retry. */
		FAILED
	}

	public final long createdAtMillis;
	public final String type;
	public final String vehicleId;
	public final String reason;
	public final boolean executable;
	public final Command command;
	public final Map<String, Object> detail = new LinkedHashMap<>();
	public Status status = Status.ADVISORY;
	public String outcome = "";

	public Decision(long createdAtMillis, String type, String vehicleId, String reason, boolean executable, Command command) {
		this.createdAtMillis = createdAtMillis;
		this.type = type;
		this.vehicleId = vehicleId;
		this.reason = reason;
		this.executable = executable;
		this.command = command;
	}

	public Map<String, Object> toJson() {
		final Map<String, Object> result = new LinkedHashMap<>();
		result.put("at", createdAtMillis);
		result.put("type", type);
		result.put("vehicle", vehicleId);
		result.put("reason", reason);
		result.put("executable", executable);
		result.put("status", status.name());
		result.put("outcome", outcome);
		result.put("detail", detail);
		if (command != null) {
			result.put("command", command.toJson());
		}
		return result;
	}

	@Override
	public String toString() {
		return type + "[" + vehicleId + "] " + status + (outcome.isEmpty() ? "" : " (" + outcome + ")") + " - " + reason;
	}
}
