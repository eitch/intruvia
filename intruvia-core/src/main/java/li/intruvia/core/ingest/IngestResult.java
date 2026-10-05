// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.ingest;

import java.util.UUID;
import li.intruvia.core.model.GeoStatus;
import li.strolch.service.api.ServiceResult;
import li.strolch.service.api.ServiceResultState;

public final class IngestResult extends ServiceResult {
	public enum Outcome { CREATED, DUPLICATE, CONFLICT, INVALID, FORBIDDEN, UNAVAILABLE }

	/** geoStatus is absent on duplicates: the retained receipt does not require the event to exist. */
	public record Accepted(UUID id, String sequence, boolean duplicate, GeoStatus geoStatus) {}

	private final Outcome outcome;
	private final Accepted accepted;

	public IngestResult() {
		this(Outcome.UNAVAILABLE, null);
	}

	public IngestResult(Outcome outcome, Accepted accepted) {
		super(outcome == Outcome.CREATED || outcome == Outcome.DUPLICATE ? ServiceResultState.SUCCESS : ServiceResultState.FAILED);
		this.outcome = outcome;
		this.accepted = accepted;
		setMessage(outcome.name());
	}

	public Outcome outcome() { return this.outcome; }
	public Accepted accepted() { return this.accepted; }
}
