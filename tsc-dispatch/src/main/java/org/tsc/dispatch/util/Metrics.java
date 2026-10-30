package org.tsc.dispatch.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;

/**
 * Counters and gauges in Prometheus text exposition format. Label sets are encoded in the
 * metric name itself ({@code commands_total{result=ok}}), which keeps call sites trivial and
 * avoids allocating a label-map per increment on the hot path.
 */
public final class Metrics {

	private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
	private final Map<String, DoubleSupplier> gauges = new ConcurrentHashMap<>();
	private final Map<String, Double> gaugeValues = new ConcurrentHashMap<>();

	public void inc(String name) {
		inc(name, 1);
	}

	public void inc(String name, long delta) {
		counters.computeIfAbsent(name, key -> new LongAdder()).add(delta);
	}

	public void gauge(String name, double value) {
		gaugeValues.put(name, value);
	}

	public void gauge(String name, DoubleSupplier supplier) {
		gauges.put(name, supplier);
	}

	public long counter(String name) {
		final LongAdder adder = counters.get(name);
		return adder == null ? 0 : adder.sum();
	}

	public double gaugeValue(String name) {
		if (gaugeValues.containsKey(name)) {
			return gaugeValues.get(name);
		}
		final DoubleSupplier supplier = gauges.get(name);
		return supplier == null ? Double.NaN : supplier.getAsDouble();
	}

	public Map<String, Number> snapshot() {
		final Map<String, Number> result = new TreeMap<>();
		counters.forEach((name, adder) -> result.put(name, adder.sum()));
		gauges.forEach((name, supplier) -> result.put(name, supplier.getAsDouble()));
		gaugeValues.forEach(result::put);
		return result;
	}

	public String render() {
		final Map<String, Number> all = new LinkedHashMap<>();
		all.putAll(snapshot());
		final StringBuilder builder = new StringBuilder();
		builder.append("# TYPE tsc_dispatch_info gauge\n");
		builder.append("tsc_dispatch_info{service=\"tsc-dispatch\"} 1\n");
		for (final Map.Entry<String, Number> entry : all.entrySet()) {
			final String name = entry.getKey();
			final Number value = entry.getValue();
			final boolean counter = counters.containsKey(name);
			builder.append("# TYPE ").append(name).append(counter ? " counter" : " gauge").append('\n');
			builder.append(name).append(' ').append(format(value.doubleValue())).append('\n');
		}
		return builder.toString();
	}

	private static String format(double value) {
		if (Double.isNaN(value)) {
			return "0";
		}
		if (value == Math.rint(value) && Math.abs(value) < 9.0E15) {
			return Long.toString((long) value);
		}
		return Double.toString(value);
	}
}
