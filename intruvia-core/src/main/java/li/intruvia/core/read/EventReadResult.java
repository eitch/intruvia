// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.read;

import li.intruvia.core.model.SecurityEvent;
import li.strolch.service.api.ServiceResult;
import li.strolch.service.api.ServiceResultState;

import java.util.List;

public final class EventReadResult extends ServiceResult {
	public enum Outcome {
		OK,
		INVALID,
		EXPIRED,
		UNAVAILABLE
	}

	public record Snapshot(List<SecurityEvent> items, String cursor, String minAfter, boolean truncated) {
		public Snapshot {
			items = List.copyOf(items);
		}
	}

	public record Page(List<SecurityEvent> items, String nextAfter, boolean hasMore, String through) {
		public Page {
			items = List.copyOf(items);
		}
	}

	private final Outcome outcome;
	private final Object body;

	public EventReadResult() {
		this(Outcome.UNAVAILABLE, null);
	}

	public EventReadResult(Outcome outcome, Object body) {
		super(outcome == Outcome.OK ? ServiceResultState.SUCCESS : ServiceResultState.FAILED);
		this.outcome = outcome;
		this.body = body;
		setMessage(outcome.name());
	}

	public Outcome outcome() {
		return this.outcome;
	}

	public Object body() {
		return this.body;
	}
}
