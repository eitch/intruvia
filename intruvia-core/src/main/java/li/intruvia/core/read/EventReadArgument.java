// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.read;

import li.strolch.service.api.ServiceArgument;

/**
 * Trusted parsed cursors; null through captures the current head. No caller-selected realm.
 */
public final class EventReadArgument extends ServiceArgument {
	public final boolean snapshot;
	public final long after;
	public final Long through;
	public final int limit;

	public EventReadArgument(boolean snapshot, long after, Long through, int limit) {
		this.snapshot = snapshot;
		this.after = after;
		this.through = through;
		this.limit = limit;
	}
}
