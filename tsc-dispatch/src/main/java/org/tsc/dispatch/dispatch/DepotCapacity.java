package org.tsc.dispatch.dispatch;

import org.tsc.dispatch.config.Config;
import org.tsc.dispatch.model.Vehicle;
import org.tsc.dispatch.model.WorldSnapshot;

/**
 * Depot capacity as a hard constraint.
 *
 * <p>MTR happily sends every train back to the same depot; with a small yard that produces a
 * queue that never clears. The service therefore refuses a return when the target depot is
 * already at its configured capacity, and looks for another depot instead of queueing.</p>
 *
 * <p>Occupancy counts vehicles the telemetry says are inside a depot plus vehicles whose
 * assigned depot is that one and that are not the vehicle asking.</p>
 */
public final class DepotCapacity {

	private DepotCapacity() {
	}

	public static int capacity(Config config, String depotName) {
		return config.depotCapacity(depotName);
	}

	public static int occupancy(WorldSnapshot snapshot, String depotName, String excludeVehicleId) {
		int count = 0;
		for (final Vehicle vehicle : snapshot.vehicles) {
			if (excludeVehicleId != null && excludeVehicleId.equals(vehicle.id)) {
				continue;
			}
			final boolean matches = depotName.equals(vehicle.depotName);
			if (vehicle.inDepot && (matches || depotName.isEmpty())) {
				count++;
			} else if (matches) {
				count++;
			}
		}
		return count;
	}

	public static boolean hasRoom(Config config, WorldSnapshot snapshot, String depotName, String vehicleId) {
		final int limit = capacity(config, depotName);
		if (limit <= 0) {
			return true;
		}
		return occupancy(snapshot, depotName, vehicleId) < limit;
	}

	public static String describe(Config config, WorldSnapshot snapshot, String depotName) {
		final int limit = capacity(config, depotName);
		return occupancy(snapshot, depotName, null) + "/" + (limit <= 0 ? "inf" : String.valueOf(limit));
	}
}
