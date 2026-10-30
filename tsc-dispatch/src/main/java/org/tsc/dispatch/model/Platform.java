package org.tsc.dispatch.model;

/**
 * A physical platform. {@code hex} is the 16 character id TSC uses on the wire;
 * {@code stationHex} links it to its owning station, which the arrivals query resolves.
 */
public record Platform(long id, String hex, String name, String stationHex, long dwellMillis) {
}
