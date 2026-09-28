// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.model;

import java.time.Instant;

public final class ContractValues {
	private ContractValues() {}

	public static String sequence(String value) {
		if (value == null || !value.matches("0|[1-9][0-9]*") || Long.parseLong(value) < 0)
			throw new IllegalArgumentException("Sequence must be a nonnegative signed 64-bit decimal string");
		return value;
	}

	public static String timestamp(String value) {
		return value == null ? null : Instant.parse(value).toString();
	}

	public static void version(int version) {
		if (version != 1)
			throw new IllegalArgumentException("Unsupported schema version: " + version);
	}
}
