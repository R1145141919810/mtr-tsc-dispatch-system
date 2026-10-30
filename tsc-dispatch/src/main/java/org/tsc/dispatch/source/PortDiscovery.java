package org.tsc.dispatch.source;

import org.tsc.dispatch.config.Config;
import org.tsc.dispatch.util.Log;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the port the simulator actually listens on.
 *
 * <p>The mod asks the operating system for a free port when its configured one is taken, so a
 * service pinned to {@code :8888} silently talks to nothing after a restart. Rather than
 * trusting the configured port, the service probes it and then a bounded range, and reports
 * the port it verified.</p>
 */
public final class PortDiscovery {

	public record Result(String baseUrl, int port, boolean discovered, List<Integer> tried) {

		public Map<String, Object> toJson() {
			final Map<String, Object> result = new LinkedHashMap<>();
			result.put("baseUrl", baseUrl);
			result.put("port", port);
			result.put("discovered", discovered);
			result.put("tried", tried);
			return result;
		}
	}

	private PortDiscovery() {
	}

	public static Result resolve(Config config, HttpJson http, int dimension) {
		final String baseUrl = config.get("tsc.base_url");
		final List<Integer> tried = new ArrayList<>();
		final URI uri;
		try {
			uri = URI.create(baseUrl);
		} catch (Exception exception) {
			return new Result(baseUrl, -1, false, tried);
		}
		final int configuredPort = uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
		if (probe(http, baseUrl, dimension)) {
			return new Result(baseUrl, configuredPort, true, List.of(configuredPort));
		}
		tried.add(configuredPort);
		if (!config.getBool("tsc.port_discovery.enabled")) {
			return new Result(baseUrl, configuredPort, false, tried);
		}

		final int start = config.getInt("tsc.port_discovery.range_start");
		final int end = config.getInt("tsc.port_discovery.range_end");
		for (int port = start; port <= end; port++) {
			if (port == configuredPort) {
				continue;
			}
			tried.add(port);
			final String candidate = rebuild(uri, port);
			if (probe(http, candidate, dimension)) {
				Log.warn("configured port unreachable, discovered live port", "configured", configuredPort, "actual", port, "url", candidate);
				return new Result(candidate, port, true, tried);
			}
			if (tried.size() >= 32) {
				break;
			}
		}
		Log.error("no TSC webserver answered on the configured port or the discovery range", "url", baseUrl, "range", start + "-" + end);
		return new Result(baseUrl, configuredPort, false, tried);
	}

	private static String rebuild(URI uri, int port) {
		final String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
		return uri.getScheme() + "://" + host + ":" + port + (uri.getPath() == null ? "" : uri.getPath());
	}

	private static boolean probe(HttpJson http, String baseUrl, int dimension) {
		try {
			final Object payload = http.get(stripTrailingSlash(baseUrl) + "/mtr/api/map/stations-and-routes?dimension=" + dimension);
			return payload instanceof Map<?, ?> map && map.containsKey("stations");
		} catch (Exception exception) {
			return false;
		}
	}

	private static String stripTrailingSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}
}
