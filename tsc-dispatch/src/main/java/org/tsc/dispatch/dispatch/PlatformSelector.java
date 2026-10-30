package org.tsc.dispatch.dispatch;

import org.tsc.dispatch.model.Platform;
import org.tsc.dispatch.model.Vehicle;
import org.tsc.dispatch.model.WorldSnapshot;
import org.tsc.dispatch.plan.Occupancy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Picks a platform for a vehicle about to call at a station.
 *
 * <p>Candidates come from the platforms observed at that station (the stock API only reveals
 * platforms through arrivals, which is why discovery is part of telemetry). Ranking is
 * deterministic: keep the current platform when it is free, otherwise the platform that frees
 * up first, with the platform name as a stable tie break so repeated runs agree.</p>
 */
public final class PlatformSelector {

	public record Choice(String platformHex, String platformName, boolean changed, String reason, long freeAtMillis) {
	}

	private PlatformSelector() {
	}

	public static Choice select(WorldSnapshot snapshot, Occupancy occupancy, Vehicle vehicle, Set<String> claimed,
								long nowMillis, long headwayMillis) {
		return select(snapshot, occupancy, vehicle, claimed, nowMillis, headwayMillis, null);
	}

	public static Choice select(WorldSnapshot snapshot, Occupancy occupancy, Vehicle vehicle, Set<String> claimed,
								long nowMillis, long headwayMillis, org.tsc.dispatch.config.PlatformPolicy policy) {
		final List<Platform> candidates = new ArrayList<>();
		for (final Platform platform : snapshot.platformsOf(vehicle.stationHex)) {
			if (policy == null || policy.allows(vehicle.routeHex, vehicle.routeNumber, vehicle.stationHex, platform.hex())) {
				candidates.add(platform);
			}
		}
		if (candidates.size() <= 1) {
			return new Choice(vehicle.platformHex, "", false,
				candidates.isEmpty() ? "no platform allowed by platform_allow" : "only one platform allowed", vehicle.etaMillis);
		}

		final List<Choice> evaluated = new ArrayList<>();
		for (final Platform platform : candidates) {
			if (platform.hex().equals(vehicle.platformHex)) {
				continue;
			}
			if (claimed.contains(platform.hex())) {
				continue;
			}
			final long freeAt = freeAt(occupancy, platform.hex(), Math.max(nowMillis, vehicle.etaMillis), vehicle.id, headwayMillis);
			final long delay = Math.max(0L, freeAt - vehicle.etaMillis);
			evaluated.add(new Choice(platform.hex(), platform.name(), false, delay == 0 ? "free" : "waits " + delay / 1000 + "s", freeAt));
		}

		if (evaluated.isEmpty()) {
			return new Choice(vehicle.platformHex, "", false, "no alternative platform free", vehicle.etaMillis);
		}
		evaluated.sort(Comparator.comparingLong(Choice::freeAtMillis).thenComparing(Choice::platformName));

		final boolean currentClaimed = claimed.contains(vehicle.platformHex);
		final long currentFreeAt = currentClaimed
			? Long.MAX_VALUE
			: freeAt(occupancy, vehicle.platformHex, Math.max(nowMillis, vehicle.etaMillis), vehicle.id, headwayMillis);
		final Choice best = evaluated.get(0);
		if (!currentClaimed && currentFreeAt <= vehicle.etaMillis + headwayMillis && currentFreeAt <= best.freeAtMillis()) {
			return new Choice(vehicle.platformHex, "", false, "current platform acceptable", currentFreeAt);
		}
		final String reason = currentClaimed
			? "current platform already claimed by an earlier train"
			: "current platform busy for another " + Math.max(0, currentFreeAt - vehicle.etaMillis) / 1000 + "s";
		return new Choice(best.platformHex(), best.platformName(), true, reason, best.freeAtMillis());
	}

	/** Earliest instant at which no other vehicle occupies the platform within the headway window. */
	public static long freeAt(Occupancy occupancy, String platformHex, long fromMillis, String selfVehicleId, long headwayMillis) {
		final String resource = "platform:" + platformHex;
		long cursor = fromMillis;
		boolean moved = true;
		while (moved) {
			moved = false;
			for (final Occupancy.Slot slot : occupancy.slots(resource)) {
				if (selfVehicleId != null && selfVehicleId.equals(slot.vehicleId())) {
					continue;
				}
				if (slot.busyFrom() <= cursor + headwayMillis && cursor < slot.busyUntil()) {
					cursor = slot.busyUntil() + 1;
					moved = true;
				}
			}
		}
		return cursor;
	}
}
