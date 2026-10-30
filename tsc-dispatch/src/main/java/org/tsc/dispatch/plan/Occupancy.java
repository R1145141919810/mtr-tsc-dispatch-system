package org.tsc.dispatch.plan;

import org.tsc.dispatch.model.Platform;
import org.tsc.dispatch.model.Vehicle;
import org.tsc.dispatch.model.WorldSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Time windowed resource occupancy derived from telemetry, optionally overlaid with the
 * leases this service itself handed out.
 *
 * <p>Every slot is an interval {@code [busyFrom, busyUntil)}. A platform slot comes from a
 * vehicle predicted to arrive there; a station slot aggregates its platforms so the planner
 * can reason about station capacity without knowing which platform a route will use.</p>
 */
public final class Occupancy {

	public record Slot(String resource, String vehicleId, long busyFrom, long busyUntil, boolean lease) {
		public boolean overlaps(long fromMillis, long toMillis) {
			return busyFrom < toMillis && fromMillis < busyUntil;
		}
	}

	private final Map<String, List<Slot>> slots = new LinkedHashMap<>();

	public static Occupancy from(WorldSnapshot snapshot) {
		final Occupancy occupancy = new Occupancy();
		for (final Vehicle vehicle : snapshot.vehicles) {
			if (vehicle.platformHex == null || vehicle.platformHex.isEmpty() || "0000000000000000".equals(vehicle.platformHex)) {
				continue;
			}
			if (vehicle.inDepot) {
				// Parked at a siding: it does not occupy the platform it intends to depart to.
				continue;
			}
			final long from = vehicle.atPlatform ? Math.min(vehicle.etaMillis, snapshot.serverTimeMillis) : vehicle.etaMillis;
			final long until = Math.max(vehicle.departureMillis, from + 5_000L);
			occupancy.add("platform:" + vehicle.platformHex, vehicle.id, from, until, false);
			final Platform platform = snapshot.platforms.get(vehicle.platformHex);
			if (platform != null) {
				occupancy.add("station:" + platform.stationHex(), vehicle.id, from, until, false);
			}
		}
		return occupancy;
	}

	public void add(String resource, String vehicleId, long fromMillis, long untilMillis, boolean lease) {
		final List<Slot> list = slots.computeIfAbsent(resource, key -> new ArrayList<>());
		list.add(new Slot(resource, vehicleId, fromMillis, untilMillis, lease));
		list.sort(Comparator.comparingLong(Slot::busyFrom));
	}

	public Occupancy withLease(String resource, String vehicleId, long fromMillis, long untilMillis) {
		add(resource, vehicleId, fromMillis, untilMillis, true);
		return this;
	}

	public List<Slot> slots(String resource) {
		return slots.getOrDefault(resource, List.of());
	}

	/** True when nobody other than {@code excludeVehicleId} occupies the resource at that instant. */
	public boolean isFree(String resource, long atMillis, String excludeVehicleId) {
		for (final Slot slot : slots(resource)) {
			if (sameVehicle(slot, excludeVehicleId)) {
				continue;
			}
			if (slot.busyFrom() <= atMillis && atMillis < slot.busyUntil()) {
				return false;
			}
		}
		return true;
	}

	/** Earliest instant at or after {@code fromMillis} at which fewer than {@code capacity} vehicles overlap. */
	public long freeAt(String resource, long fromMillis, int capacity, String excludeVehicleId) {
		final List<Slot> relevant = new ArrayList<>();
		for (final Slot slot : slots(resource)) {
			if (!sameVehicle(slot, excludeVehicleId) && slot.busyUntil() > fromMillis) {
				relevant.add(slot);
			}
		}
		if (relevant.isEmpty()) {
			return fromMillis;
		}
		relevant.sort(Comparator.comparingLong(Slot::busyFrom));
		long cursor = fromMillis;
		for (final Slot slot : relevant) {
			if (slot.busyFrom() > cursor) {
				return cursor;
			}
			if (slot.busyUntil() > cursor) {
				cursor = slot.busyUntil();
			}
		}
		return cursor;
	}

	public String occupant(String resource, long atMillis, String excludeVehicleId) {
		for (final Slot slot : slots(resource)) {
			if (!sameVehicle(slot, excludeVehicleId) && slot.busyFrom() <= atMillis && atMillis < slot.busyUntil()) {
				return slot.vehicleId();
			}
		}
		return null;
	}

	private static boolean sameVehicle(Slot slot, String vehicleId) {
		return vehicleId != null && !vehicleId.isEmpty() && vehicleId.equals(slot.vehicleId());
	}

	public Map<String, Object> describe() {
		final Map<String, Object> result = new LinkedHashMap<>();
		slots.forEach((resource, list) -> {
			final List<Object> encoded = new ArrayList<>();
			for (final Slot slot : list) {
				final Map<String, Object> item = new LinkedHashMap<>();
				item.put("vehicle", slot.vehicleId());
				item.put("from", slot.busyFrom());
				item.put("until", slot.busyUntil());
				item.put("lease", slot.lease());
				encoded.add(item);
			}
			result.put(resource, encoded);
		});
		return result;
	}
}
