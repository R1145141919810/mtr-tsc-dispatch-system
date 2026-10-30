package org.tsc.dispatch.source;

import org.tsc.dispatch.config.Config;
import org.tsc.dispatch.config.Semver;
import org.tsc.dispatch.model.Route;
import org.tsc.dispatch.model.WorldSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compatibility gate.
 *
 * <p>The HTTP surface never publishes the mod version, so a version string alone would be
 * theatre. Two independent signals are combined:</p>
 * <ol>
 *   <li><b>declared version</b> - what the operator says is running, checked against the
 *       configured min/max window;</li>
 *   <li><b>API fingerprint</b> - which parts of the documented payload actually arrived.
 *       This is what protects against breakage: a version string can be stale or wrong,
 *       a missing field cannot.</li>
 * </ol>
 *
 * <p>A missing required capability (stations, routes) is fatal for the dimension; missing
 * optional ones degrade the feature that depends on them (segment durations, vehicle ids)
 * instead of failing the whole service.</p>
 */
public final class CompatibilityProbe {

	public record Result(boolean compatible, boolean degraded, String level, String declaredVersion,
						 boolean versionInRange, List<String> missing, List<String> notes) {

		public Map<String, Object> toJson() {
			final Map<String, Object> result = new LinkedHashMap<>();
			result.put("compatible", compatible);
			result.put("degraded", degraded);
			result.put("level", level);
			result.put("declaredVersion", declaredVersion);
			result.put("versionInRange", versionInRange);
			result.put("missing", missing);
			result.put("notes", notes);
			return result;
		}
	}

	private CompatibilityProbe() {
	}

	public static Result evaluate(Config config, WorldSnapshot snapshot) {
		final String declared = config.get("tsc.version.declared");
		final boolean strict = config.getBool("tsc.version.strict");
		final boolean versionKnown = declared != null && !declared.isBlank();
		final boolean inRange = versionKnown
			&& Semver.inRange(declared, config.get("tsc.version.min"), config.get("tsc.version.max"));

		final List<String> missing = new ArrayList<>();
		final List<String> notes = new ArrayList<>();

		if (!versionKnown) {
			notes.add("tsc.version.declared is empty; relying on the API fingerprint only");
		} else if (!inRange) {
			notes.add("declared version " + declared + " is outside [" + config.get("tsc.version.min") + ", "
				+ (config.get("tsc.version.max").isBlank() ? "inf" : config.get("tsc.version.max")) + "]");
		}

		boolean fatal = false;
		if (snapshot.stations.isEmpty()) {
			missing.add("stations");
			fatal = true;
		}
		if (snapshot.routes.isEmpty()) {
			missing.add("routes");
			fatal = true;
		}

		boolean durations = false;
		for (final Route route : snapshot.routes.values()) {
			durations |= route.durations().size() >= Math.max(1, route.stops().size() - 1);
		}
		if (!durations && !snapshot.routes.isEmpty()) {
			missing.add("route_durations");
			notes.add("no segment durations published; travel times are estimated from coordinates");
		}
		if (!snapshot.vehicleTelemetry) {
			missing.add("vehicle_identity");
			notes.add("no addressable vehicle ids; decisions will be advisory");
		}
		if (snapshot.platforms.isEmpty()) {
			missing.add("platforms");
			notes.add("no platforms discovered; platform selection is disabled until they appear");
		}

		final boolean versionFailure = strict && (!versionKnown || !inRange);
		final boolean compatible = !fatal && !versionFailure;
		final boolean degraded = compatible && !missing.isEmpty();
		final String level = !compatible ? "INCOMPATIBLE" : degraded ? "DEGRADED" : "COMPATIBLE";
		return new Result(compatible, degraded, level, versionKnown ? declared : "", inRange, missing, notes);
	}
}
