package org.tsc.dispatch.model;

/**
 * One controllable (or merely observed) vehicle.
 *
 * <p>Two flavours exist at runtime and the dispatcher treats them identically except for
 * {@link #controllable}: vehicles synthesised from the stock <em>arrivals</em> feed carry
 * {@code controllable = false} because the stock HTTP surface offers no write path and no
 * vehicle identity beyond the arrival slot; vehicles reported by the control bridge are
 * addressable and can be commanded.</p>
 */
public final class Vehicle {

	public String id = "";
	public String name = "";
	public String routeHex = "";
	public String routeNumber = "";
	public String platformHex = "";
	public String stationHex = "";
	public String destination = "";
	public String depotName = "";
	public String origin = "unknown";
	/** Absolute wall clock millis of the predicted arrival at {@link #platformHex}. */
	public long etaMillis;
	public long departureMillis;
	public long deviationMillis;
	public long dwellElapsedMillis;
	public long departureIndex = -1;
	/** Millis since this service run started, 0 when unknown. */
	public long serviceStartMillis;
	public double railProgress;
	public double speedMps;
	public double passengerCount;
	public boolean controllable;
	public boolean atPlatform;
	public boolean inDepot;
	/** True when the bridge reports the siding lost its outbound path; such a train cannot be dispatched. */
	public boolean pathLost;

	public String key() {
		return id;
	}

	public Vehicle copy() {
		final Vehicle copy = new Vehicle();
		copy.id = id;
		copy.name = name;
		copy.routeHex = routeHex;
		copy.routeNumber = routeNumber;
		copy.platformHex = platformHex;
		copy.stationHex = stationHex;
		copy.destination = destination;
		copy.depotName = depotName;
		copy.origin = origin;
		copy.etaMillis = etaMillis;
		copy.departureMillis = departureMillis;
		copy.deviationMillis = deviationMillis;
		copy.dwellElapsedMillis = dwellElapsedMillis;
		copy.departureIndex = departureIndex;
		copy.serviceStartMillis = serviceStartMillis;
		copy.railProgress = railProgress;
		copy.speedMps = speedMps;
		copy.passengerCount = passengerCount;
		copy.controllable = controllable;
		copy.atPlatform = atPlatform;
		copy.inDepot = inDepot;
		copy.pathLost = pathLost;
		return copy;
	}

	@Override
	public String toString() {
		return "Vehicle[" + id + " route=" + routeHex + " next=" + platformHex + " eta=" + etaMillis + " controllable=" + controllable + "]";
	}
}
