package org.tsc.dispatch.dispatch;

import org.tsc.dispatch.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One dispatch instruction.
 *
 * <p>A command is a value object: it carries the sequence number that orders it, the
 * idempotency key that prevents a duplicate from being applied after a retry or a service
 * restart, a precondition describing the state it was derived from, and a hard expiry so a
 * stale instruction can never be applied minutes later.</p>
 */
public final class Command {

	public enum Kind {
		SET_PLATFORM("set_platform"),
		REROUTE("reroute"),
		HOLD("hold"),
		RELEASE("release"),
		RETURN_TO_DEPOT("return_to_depot"),
		DEPLOY("deploy_train"),
		REFRESH_DEPOT_ROUTES("refresh_depot_routes"),
		INSTANT_DEPLOY("instant_deploy"),
		CLEAR_DEPOT("clear_depot");

		public final String wire;

		Kind(String wire) {
			this.wire = wire;
		}

		public static Kind fromWire(String wire) {
			for (final Kind kind : values()) {
				if (kind.wire.equalsIgnoreCase(wire)) {
					return kind;
				}
			}
			return null;
		}
	}

	public long seq;
	public String idempotencyKey = "";
	public Kind kind;
	public String vehicleId = "";
	public final Map<String, Object> args = new LinkedHashMap<>();
	public String preconditionVehicleId = "";
	public String preconditionPlatformHex = "";
	public long preconditionObservedAt;
	public long issuedAtMillis;
	public long expiresAtMillis;
	public String reason = "";
	public int maxAttempts = 3;

	public Map<String, Object> toJson() {
		final Map<String, Object> result = new LinkedHashMap<>();
		result.put("protocol", 1);
		result.put("seq", seq);
		result.put("key", idempotencyKey);
		result.put("kind", kind == null ? "unknown" : kind.wire);
		result.put("vehicleId", vehicleId);
		result.put("args", args);
		final Map<String, Object> precondition = new LinkedHashMap<>();
		precondition.put("vehicleId", preconditionVehicleId);
		precondition.put("platformId", preconditionPlatformHex);
		precondition.put("observedAt", preconditionObservedAt);
		result.put("precondition", precondition);
		result.put("issuedAt", issuedAtMillis);
		result.put("expiresAt", expiresAtMillis);
		result.put("reason", reason);
		return result;
	}

	public String describe() {
		return kind == null ? "unknown" : kind.wire + " " + vehicleId + " " + Json.write(args);
	}
}
