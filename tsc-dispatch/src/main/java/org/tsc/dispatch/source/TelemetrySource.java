package org.tsc.dispatch.source;

import org.tsc.dispatch.model.WorldSnapshot;

import java.util.Set;

/**
 * A telemetry adapter. Implementations must be safe to call serially from the poll thread and
 * must not mutate the returned snapshot afterwards.
 */
public interface TelemetrySource extends AutoCloseable {

	/** Short identifier used in logs and {@code /v1/status}. */
	String name();

	/** Feature flags this source actually provides, e.g. {@code vehicles}, {@code rails}. */
	Set<String> features();

	/** True when {@link org.tsc.dispatch.model.Vehicle#id} is stable and addressable. */
	boolean vehicleTelemetry();

	WorldSnapshot fetch() throws Exception;

	@Override
	default void close() {
	}
}
