package org.tsc.dispatch.actuator;

import org.tsc.dispatch.config.Config;
import org.tsc.dispatch.dispatch.Command;
import org.tsc.dispatch.source.HttpJson;
import org.tsc.dispatch.util.Json;
import org.tsc.dispatch.util.Log;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Write channel to the control bridge, protocol v1.
 *
 * <p>The bridge speaks a narrower vocabulary than this service: it can move a parked train's
 * platform, deploy a train, park it, and hold or release its dwell timer. Everything else -
 * notably re-routing a train that is already running - is not expressible, because the
 * simulator freezes a train's path for the whole run. The actuator therefore translates the
 * service's command kinds onto the bridge's wire kinds and advertises only what the bridge
 * actually reported in {@code /dispatch/v1/capabilities}.</p>
 *
 * <p>Outcome mapping matters for the dispatcher's back-off logic:</p>
 * <ul>
 *   <li>{@code APPLIED} - the world changed;</li>
 *   <li>{@code DEFERRED} - the bridge accepted an intent that will apply later; the service
 *       records {@code ACCEPTED} and waits for telemetry instead of resending;</li>
 *   <li>{@code REJECTED}/{@code FAILED} - retrying the same command cannot help;</li>
 *   <li>{@code UNAVAILABLE} - transport or simulator is not ready, retry is meaningful.</li>
 * </ul>
 */
public final class BridgeActuator implements Actuator {

	private final String baseUrl;
	private final HttpJson http;
	private final int dimension;
	private final java.util.function.Supplier<Set<String>> bridgeFeatures;

	public BridgeActuator(Config config, HttpJson http, Set<String> bridgeFeatures, int dimension) {
		this(config, http, () -> bridgeFeatures, dimension);
	}

	public BridgeActuator(Config config, HttpJson http, java.util.function.Supplier<Set<String>> bridgeFeatures, int dimension) {
		this.baseUrl = stripTrailingSlash(config.get("bridge.base-url"));
		this.http = http;
		this.dimension = dimension;
		this.bridgeFeatures = bridgeFeatures;
	}

	/**
	 * Resolved on every call, not cached at construction: a bridge that starts before its world
	 * loads advertises nothing, and the service must pick the capabilities up once it does.
	 */
	private Set<Command.Kind> resolveSupported() {
		final Set<Command.Kind> supported = new LinkedHashSet<>();
		for (final String feature : bridgeFeatures.get()) {
			switch (feature.toLowerCase(Locale.ROOT)) {
				case "set_platform" -> supported.add(Command.Kind.SET_PLATFORM);
				case "return_to_depot" -> supported.add(Command.Kind.RETURN_TO_DEPOT);
				case "deploy_train" -> supported.add(Command.Kind.DEPLOY);
				case "dwell_control" -> {
					supported.add(Command.Kind.HOLD);
					supported.add(Command.Kind.RELEASE);
				}
				case "reroute" -> supported.add(Command.Kind.REROUTE);
				case "refresh_depot_routes" -> supported.add(Command.Kind.REFRESH_DEPOT_ROUTES);
				case "instant_deploy" -> supported.add(Command.Kind.INSTANT_DEPLOY);
				case "clear_depot" -> supported.add(Command.Kind.CLEAR_DEPOT);
				default -> Log.debug("bridge advertises unknown feature", "feature", feature);
			}
		}
		return supported;
	}

	@Override
	public String kind() {
		return "bridge";
	}

	@Override
	public Set<Command.Kind> supports() {
		return Set.copyOf(resolveSupported());
	}

	@Override
	public Result execute(Command command) {
		if (!resolveSupported().contains(command.kind)) {
			return Result.fatal("bridge does not advertise " + command.kind.wire);
		}
		try {
			final Object response = http.post(baseUrl + "/dispatch/v1/command?dimension=" + dimension, wireBody(command));
			final Map<String, Object> body = Json.asObject(response);
			final String outcome = Json.string(body, "outcome", "").toUpperCase(Locale.ROOT);
			final boolean ok = Json.bool(body, "ok", false);
			final boolean applied = Json.bool(body, "applied", false);
			final boolean retryable = Json.bool(body, "retryable", false);
			String detail = Json.string(body, "detail", "");
			if (detail.isEmpty()) {
				detail = Json.string(body, "message", "");
			}

			switch (outcome) {
				case "APPLIED":
					return Result.applied(detail.isEmpty() ? "applied" : detail);
				case "DEFERRED":
					return Result.accepted(detail.isEmpty() ? "deferred by bridge" : detail);
				case "REJECTED":
				case "FAILED":
					return Result.fatal(detail.isEmpty() ? outcome.toLowerCase(Locale.ROOT) : detail);
				case "UNAVAILABLE":
					return Result.retryable(detail.isEmpty() ? "bridge unavailable" : detail);
				default:
					break;
			}
			if (ok && applied) {
				return Result.applied(detail.isEmpty() ? "bridge applied" : detail);
			}
			if (ok) {
				return Result.accepted(detail.isEmpty() ? "bridge accepted" : detail);
			}
			return retryable ? Result.retryable(detail) : Result.fatal(detail.isEmpty() ? "bridge rejected" : detail);
		} catch (HttpJson.HttpException exception) {
			if (exception.status == 409) {
				return Result.fatal("bridge conflict: " + exception.getMessage());
			}
			return exception.status >= 500 ? Result.retryable(exception.getMessage()) : Result.fatal(exception.getMessage());
		} catch (Exception exception) {
			Log.debug("bridge call failed", "error", exception.getMessage());
			return Result.retryable("bridge unreachable: " + exception.getMessage());
		}
	}

	/** Translates a service command into the bridge's compact wire format. */
	Map<String, Object> wireBody(Command command) {
		final Map<String, Object> body = new LinkedHashMap<>();
		body.put("kind", wireKind(command.kind));
		body.put("vehicleId", command.vehicleId);
		final Map<String, Object> args = new LinkedHashMap<>();
		switch (command.kind) {
			case SET_PLATFORM, DEPLOY -> args.put("platformId", command.args.getOrDefault("platformId", ""));
			case RETURN_TO_DEPOT -> args.put("sidingId", command.args.getOrDefault("sidingId", ""));
			case HOLD -> args.put("seconds", command.args.getOrDefault("seconds", 30));
			case RELEASE -> args.put("seconds", 0);
			default -> args.putAll(command.args);
		}
		body.put("args", args);
		body.put("key", command.idempotencyKey);
		body.put("reason", command.reason);
		return body;
	}

	private static String wireKind(Command.Kind kind) {
		return switch (kind) {
			case HOLD, RELEASE -> "dwell_control";
			case DEPLOY -> "deploy_train";
			default -> kind.wire;
		};
	}

	private static String stripTrailingSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}
}
