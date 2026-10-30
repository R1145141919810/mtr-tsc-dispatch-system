package org.tsc.dispatch.dispatch;

import org.tsc.dispatch.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Soft resource leases: the in-process arbitration layer that stops two decisions from
 * claiming the same platform or depot siding at the same time.
 *
 * <p>Leases are advisory with respect to the simulator (TSC is the source of truth) but
 * authoritative between concurrent decision passes of this service. They expire, they can be
 * preempted by an earlier vehicle, and they are persisted so a restart does not immediately
 * hand the same platform to somebody else.</p>
 */
public final class LeaseTable {

	public record Lease(String resource, String vehicleId, long expiresAtMillis, long grantedAtMillis, long priorityMillis) {
	}

	private final Map<String, Lease> leases = new LinkedHashMap<>();

	public synchronized boolean tryAcquire(String resource, String vehicleId, long nowMillis, long ttlMillis, long priorityMillis) {
		final Lease existing = leases.get(resource);
		if (existing == null || existing.vehicleId().equals(vehicleId) || existing.expiresAtMillis() <= nowMillis) {
			leases.put(resource, new Lease(resource, vehicleId, nowMillis + ttlMillis, nowMillis, priorityMillis));
			return true;
		}
		// Preempt only when the requester is meaningfully earlier and the holder is not locked in.
		if (priorityMillis + 1_000L < existing.priorityMillis()) {
			leases.put(resource, new Lease(resource, vehicleId, nowMillis + ttlMillis, nowMillis, priorityMillis));
			return true;
		}
		return false;
	}

	public synchronized void release(String resource, String vehicleId) {
		final Lease existing = leases.get(resource);
		if (existing != null && existing.vehicleId().equals(vehicleId)) {
			leases.remove(resource);
		}
	}

	public synchronized void releaseAll(String vehicleId) {
		leases.entrySet().removeIf(entry -> entry.getValue().vehicleId().equals(vehicleId));
	}

	public synchronized String holder(String resource) {
		final Lease lease = leases.get(resource);
		return lease == null ? null : lease.vehicleId();
	}

	public synchronized void expire(long nowMillis) {
		leases.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= nowMillis);
	}

	public synchronized List<Lease> snapshot() {
		return new ArrayList<>(leases.values());
	}

	public synchronized Map<String, Object> describe() {
		final Map<String, Object> result = new LinkedHashMap<>();
		leases.forEach((resource, lease) -> {
			final Map<String, Object> item = new LinkedHashMap<>();
			item.put("vehicle", lease.vehicleId());
			item.put("expiresAt", lease.expiresAtMillis());
			result.put(resource, item);
		});
		return result;
	}

	public synchronized Map<String, Object> toJson() {
		final Map<String, Object> result = new LinkedHashMap<>();
		final List<Object> items = new ArrayList<>();
		for (final Lease lease : leases.values()) {
			final Map<String, Object> item = new LinkedHashMap<>();
			item.put("resource", lease.resource());
			item.put("vehicle", lease.vehicleId());
			item.put("expiresAt", lease.expiresAtMillis());
			item.put("grantedAt", lease.grantedAtMillis());
			item.put("priority", lease.priorityMillis());
			items.add(item);
		}
		result.put("leases", items);
		return result;
	}

	public synchronized void load(Map<String, Object> json, long nowMillis) {
		leases.clear();
		for (final Object element : Json.asArray(json.get("leases"))) {
			final Map<String, Object> item = Json.asObject(element);
			final String resource = Json.string(item, "resource", "");
			final String vehicle = Json.string(item, "vehicle", "");
			final long expiresAt = Json.number(item, "expiresAt", 0);
			if (resource.isEmpty() || vehicle.isEmpty() || expiresAt <= nowMillis) {
				continue;
			}
			leases.put(resource, new Lease(resource, vehicle, expiresAt, Json.number(item, "grantedAt", nowMillis), Json.number(item, "priority", 0)));
		}
	}
}
