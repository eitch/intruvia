// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.ingest;

/** Safe validation failure; never retains inbound values or parser exceptions. */
public final class InvalidEventException extends IllegalArgumentException {
	private final boolean oversized;

	public InvalidEventException(boolean oversized) {
		super(oversized ? "Event body exceeds limit" : "Invalid event payload");
		this.oversized = oversized;
	}

	public boolean isOversized() { return this.oversized; }
}
