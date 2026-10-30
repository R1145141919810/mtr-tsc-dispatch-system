package org.tsc.dispatch.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform allow-set.
 *
 * <p>Evaluation is intentionally simple so operators can predict it:</p>
 * <ol>
 *   <li>collect every rule matching the (route, station) pair;</li>
 *   <li>if there are none, apply {@code default} (allow or deny);</li>
 *   <li>otherwise the platform is allowed when it appears in at least one allow-list and in no
 *       deny-list.</li>
 * </ol>
 *
 * <p>The stock API does not publish which platform a route is scheduled to use, so this policy
 * is also the only place where an operator can express "route A must never call at Central 2".</p>
 */
public final class PlatformPolicy {

	public enum DefaultPolicy {
		ALLOW, DENY;

		public static DefaultPolicy parse(String value) {
			return "deny".equalsIgnoreCase(value) ? DENY : ALLOW;
		}
	}

	private final DefaultPolicy defaultPolicy;
	private final List<PlatformRule> rules;

	public PlatformPolicy(DefaultPolicy defaultPolicy, List<PlatformRule> rules) {
		this.defaultPolicy = defaultPolicy == null ? DefaultPolicy.ALLOW : defaultPolicy;
		this.rules = rules == null ? List.of() : List.copyOf(rules);
	}

	public static PlatformPolicy allowAll() {
		return new PlatformPolicy(DefaultPolicy.ALLOW, List.of());
	}

	public boolean hasRules() {
		return !rules.isEmpty();
	}

	public DefaultPolicy defaultPolicy() {
		return defaultPolicy;
	}

	public List<PlatformRule> rules() {
		return rules;
	}

	public boolean allows(String routeHex, String routeNumber, String stationHex, String platformHex) {
		if (rules.isEmpty()) {
			return defaultPolicy == DefaultPolicy.ALLOW;
		}
		boolean matched = false;
		boolean allowed = false;
		for (final PlatformRule rule : rules) {
			if (!rule.matches(routeHex, routeNumber, stationHex)) {
				continue;
			}
			matched = true;
			if (rule.deny()) {
				if (rule.contains(platformHex)) {
					return false;
				}
			} else if (rule.contains(platformHex)) {
				allowed = true;
			}
		}
		return matched ? allowed : defaultPolicy == DefaultPolicy.ALLOW;
	}

	/**
	 * Referential check against the live topology: every station/platform mentioned by a rule
	 * must exist. Unknown ids are the most common configuration mistake and silently ignoring
	 * them turns an allow-list into "nothing is allowed".
	 */
	public List<String> validate(String stationHex, String platformHex) {
		final List<String> problems = new ArrayList<>();
		if (stationHex != null && !stationHex.isBlank() && !"*".equals(stationHex)) {
			problems.add("unknown station " + stationHex);
		}
		if (platformHex != null && !platformHex.isBlank() && !"*".equals(platformHex)) {
			problems.add("unknown platform " + platformHex);
		}
		return problems;
	}

	public Map<String, Object> describe() {
		final Map<String, Object> result = new LinkedHashMap<>();
		result.put("default", defaultPolicy.name().toLowerCase(java.util.Locale.ROOT));
		final List<Object> encoded = new ArrayList<>();
		for (final PlatformRule rule : rules) {
			final Map<String, Object> item = new LinkedHashMap<>();
			item.put("route", rule.route());
			item.put("station", rule.station());
			item.put("platforms", rule.platforms());
			item.put("deny", rule.deny());
			encoded.add(item);
		}
		result.put("rules", encoded);
		return result;
	}
}
