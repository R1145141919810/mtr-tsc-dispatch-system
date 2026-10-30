package org.tsc.dispatch.actuator;

import org.tsc.dispatch.dispatch.Command;
import org.tsc.dispatch.util.Log;
import org.tsc.dispatch.util.Metrics;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Routes each command to the best channel that can actually carry it.
 *
 * <p>Order: bridge (when online) -> RCON (only for the coarse kinds it supports) -> nothing.
 * "Nothing" is a first-class outcome: the command bean reports it and the dispatcher turns the
 * decision into an advisory, rather than pretending the train was moved.</p>
 *
 * <p>Failover is policy driven ({@code rcon.fallback_on}) so an operator can decide whether a
 * rejected command should immediately be retried through the console or stay a rejection.</p>
 */
public final class FailoverActuator implements Actuator {

	private final Actuator bridge;
	private final Actuator rcon;
	private final Set<String> fallbackOn;
	private final Metrics metrics;

	private volatile boolean bridgeOnline;
	private volatile String lastPrimaryError = "";
	private final AtomicLong fallbacks = new AtomicLong();
	private final AtomicLong primaryFailures = new AtomicLong();

	public FailoverActuator(Actuator bridge, Actuator rcon, Set<String> fallbackOn, Metrics metrics) {
		this.bridge = bridge;
		this.rcon = rcon;
		this.fallbackOn = fallbackOn == null ? Set.of() : Set.copyOf(fallbackOn);
		this.metrics = metrics;
		this.bridgeOnline = bridge != null;
	}

	public void markBridgeOnline(boolean online, String reason) {
		if (this.bridgeOnline != online) {
			Log.warn("control bridge state changed", "online", online, "reason", reason);
		}
		this.bridgeOnline = online;
		if (!online) {
			this.lastPrimaryError = reason;
		}
	}

	public boolean bridgeOnline() {
		return bridge != null && bridgeOnline;
	}

	@Override
	public String kind() {
		if (bridgeOnline()) {
			return "bridge";
		}
		return rcon != null ? "rcon" : "observe";
	}

	@Override
	public Set<Command.Kind> supports() {
		final Set<Command.Kind> kinds = new LinkedHashSet<>();
		if (bridgeOnline()) {
			kinds.addAll(bridge.supports());
		}
		if (rcon != null) {
			kinds.addAll(rcon.supports());
		}
		return Set.copyOf(kinds);
	}

	@Override
	public Result execute(Command command) {
		if (bridgeOnline() && bridge.supports().contains(command.kind)) {
			final Result result = bridge.execute(command);
			if (result.success()) {
				return result;
			}
			primaryFailures.incrementAndGet();
			final boolean connectivity = result.retryable();
			final boolean allowFailover = connectivity
				? fallbackOn.contains("command_timeout") || fallbackOn.contains("bridge_offline")
				: fallbackOn.contains("bridge_reject");
			if (connectivity) {
				markBridgeOnline(false, result.detail());
			}
			if (!allowFailover) {
				return result;
			}
			metrics.inc("dispatch_failover_total{reason=" + (connectivity ? "offline" : "reject") + "}");
			fallbacks.incrementAndGet();
			Log.warn("falling back after bridge failure", "kind", command.kind.wire, "detail", result.detail());
		}

		if (rcon != null && rcon.supports().contains(command.kind)) {
			return rcon.execute(command);
		}
		return Result.fatal("no channel available for " + command.kind.wire
			+ (bridgeOnline() ? " (bridge rejected, RCON cannot carry it)" : " (bridge offline, RCON cannot carry it)"));
	}

	public Map<String, Object> status() {
		final Map<String, Object> result = new LinkedHashMap<>();
		result.put("active", kind());
		result.put("bridgeConfigured", bridge != null);
		result.put("bridgeOnline", bridgeOnline());
		result.put("rconConfigured", rcon != null);
		result.put("fallbackTriggers", fallbackOn);
		result.put("fallbacks", fallbacks.get());
		result.put("primaryFailures", primaryFailures.get());
		result.put("lastPrimaryError", lastPrimaryError);
		final Set<String> supported = new LinkedHashSet<>();
		supports().forEach(candidate -> supported.add(candidate.wire));
		result.put("supportedCommands", supported);
		return result;
	}

	@Override
	public void close() {
		if (bridge != null) {
			bridge.close();
		}
		if (rcon != null) {
			rcon.close();
		}
	}
}
