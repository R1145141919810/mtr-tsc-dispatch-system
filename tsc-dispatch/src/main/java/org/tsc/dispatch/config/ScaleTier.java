package org.tsc.dispatch.config;

/** Adaptive polling tier: up to {@code maxTrains} trains, poll every {@code pollingIntervalMs}. */
public record ScaleTier(int maxTrains, long pollingIntervalMs) {
}
