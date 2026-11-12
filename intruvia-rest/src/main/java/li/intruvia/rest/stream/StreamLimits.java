// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.stream;

import li.strolch.runtime.configuration.ComponentConfiguration;

/** Explicit finite transport bounds; configured only at startup. */
public record StreamLimits(int queueFrames, int messageBytes, int sendSeconds, int pollMillis) {
	public static final StreamLimits DEFAULT = new StreamLimits(1000, 65536, 10, 250);
	public StreamLimits {
		if (queueFrames < 1 || queueFrames > 1000 || messageBytes < 1024 || messageBytes > 65536 ||
				sendSeconds < 1 || sendSeconds > 30 || pollMillis < 10 || pollMillis > 1000)
			throw new IllegalArgumentException("Invalid EventStream queueFrames/messageBytes/sendSeconds/pollMillis bounds");
	}
	static StreamLimits load(ComponentConfiguration config) {
		if (!java.util.Set.of("queueFrames", "messageBytes", "sendSeconds", "pollMillis").containsAll(config.getPropertyKeys()))
			throw new IllegalArgumentException("Unknown EventStream setting");
		try {
			return new StreamLimits(number(config, "queueFrames", "1000"), number(config, "messageBytes", "65536"),
					number(config, "sendSeconds", "10"), number(config, "pollMillis", "250"));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid EventStream setting");
		}
	}
	private static int number(ComponentConfiguration config, String key, String fallback) {
		return Integer.parseInt(config.getAsMap().getOrDefault(key, fallback));
	}
}
