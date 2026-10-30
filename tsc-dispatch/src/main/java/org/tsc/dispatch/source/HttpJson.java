package org.tsc.dispatch.source;

import org.tsc.dispatch.util.Json;
import org.tsc.dispatch.util.Log;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Thin JSON over HTTP helper built on the JDK client.
 *
 * <p>Two TSC specific behaviours live here rather than in every caller: the response
 * envelope ({@code {status, text, currentTime, data}}) is unwrapped, and transient transport
 * failures are retried with jittered backoff. A 4xx is never retried because it means the
 * request itself is wrong.</p>
 */
public final class HttpJson implements AutoCloseable {

	private final HttpClient client;
	private final Duration timeout;
	private final int retries;
	private final String userAgent;
	private final String bearer;

	public HttpJson(Duration timeout, int retries, String userAgent) {
		this(timeout, retries, userAgent, "");
	}

	public HttpJson(Duration timeout, int retries, String userAgent, String bearer) {
		this.timeout = timeout;
		this.retries = Math.max(0, retries);
		this.userAgent = userAgent;
		this.bearer = bearer == null ? "" : bearer.trim();
		this.client = HttpClient.newBuilder()
			.connectTimeout(timeout)
			.followRedirects(HttpClient.Redirect.NORMAL)
			.version(HttpClient.Version.HTTP_1_1)
			.build();
	}

	public static final class HttpException extends RuntimeException {
		public final int status;

		public HttpException(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	public Object get(String url) {
		return exchange("GET", url, null);
	}

	public Object post(String url, Object body) {
		return exchange("POST", url, Json.write(body));
	}

	private Object exchange(String method, String url, String body) {
		RuntimeException last = null;
		for (int attempt = 0; attempt <= retries; attempt++) {
			try {
				final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
					.timeout(timeout)
					.header("Accept", "application/json")
					.header("User-Agent", userAgent);
				if (!bearer.isEmpty()) {
					builder.header("Authorization", "Bearer " + bearer);
				}
				if ("GET".equals(method)) {
					builder.GET();
				} else {
					builder.header("Content-Type", "application/json");
					builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body, StandardCharsets.UTF_8));
				}
				final HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
				if (response.statusCode() >= 400) {
					final HttpException exception = new HttpException(response.statusCode(), "HTTP " + response.statusCode() + " " + url);
					if (response.statusCode() < 500) {
						throw exception;
					}
					last = exception;
				} else {
					final String text = response.body();
					return text == null || text.isBlank() ? null : unwrap(Json.parse(text));
				}
			} catch (HttpException exception) {
				if (exception.status < 500) {
					throw exception;
				}
				last = exception;
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("interrupted while calling " + url, exception);
			} catch (Exception exception) {
				last = new RuntimeException(exception.getClass().getSimpleName() + " calling " + url + ": " + exception.getMessage(), exception);
			}
			if (attempt < retries) {
				final long backoff = 80L * (1L << attempt) + ThreadLocalRandom.current().nextLong(40);
				try {
					Thread.sleep(backoff);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		throw last == null ? new RuntimeException("request failed: " + url) : last;
	}

	/** Unwraps the TSC envelope; returns the payload unchanged when it is not enveloped. */
	static Object unwrap(Object parsed) {
		if (parsed instanceof Map<?, ?> map && map.containsKey("data") && (map.containsKey("status") || map.containsKey("code"))) {
			return map.get("data");
		}
		return parsed;
	}

	@Override
	public void close() {
		// java.net.http.HttpClient is not closeable on Java 17; its connections are released
		// when the process exits, which is the lifetime of this service.
	}

	static Map<String, Object> objectOrEmpty(Object value) {
		return Json.asObject(value);
	}

	static void logDebugFailure(String what, Exception exception) {
		Log.debug("http request failed", "what", what, "error", exception.getMessage());
	}
}
