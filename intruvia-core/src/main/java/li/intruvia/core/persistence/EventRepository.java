// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import li.intruvia.core.model.EventModelMapper;
import li.intruvia.core.model.EventStreamState;
import li.intruvia.core.model.IngestReceipt;
import li.intruvia.core.model.SecurityEvent;
import li.strolch.persistence.api.StrolchTransaction;
import li.strolch.search.ResourceSearch;

import static li.intruvia.core.model.ModelConstants.*;

/** Caller owns the transaction, authorization and commit. No mutable model objects escape reads. */
public final class EventRepository {
	private final EventModelMapper mapper = new EventModelMapper();

	public Optional<SecurityEvent> event(StrolchTransaction tx, UUID id) {
		return Optional.ofNullable(tx.getResourceBy(TYPE_SECURITY_EVENT, id.toString())).map(this.mapper::toEvent);
	}

	public Optional<IngestReceipt> receipt(StrolchTransaction tx, String id) {
		return Optional.ofNullable(tx.getResourceBy(TYPE_INGEST_RECEIPT, id)).map(this.mapper::toReceipt);
	}

	public EventStreamState streamState(StrolchTransaction tx) {
		return this.mapper.toStreamState(tx.getResourceBy(TYPE_EVENT_STREAM_STATE, STREAM_ID, true));
	}

	/** Internal bounded search boundary; HTTP cursor/retention contracts belong to task 012. */
	public List<SecurityEvent> events(StrolchTransaction tx, int limit) {
		if (limit < 1 || limit > 1000)
			throw new IllegalArgumentException("Event limit must be between 1 and 1000");
		return new ResourceSearch().types(TYPE_SECURITY_EVENT).search(tx)
				.orderByParam(BAG_ENVELOPE, PARAM_SEQUENCE, false).toList().stream()
				.limit(limit).map(this.mapper::toEvent).toList();
	}

	public void addEvent(StrolchTransaction tx, SecurityEvent event) {
		tx.add(this.mapper.toResource(event));
	}

	public void addReceipt(StrolchTransaction tx, IngestReceipt receipt) {
		tx.add(this.mapper.toResource(receipt));
	}

	public void updateStreamState(StrolchTransaction tx, EventStreamState state) {
		var resource = tx.readLock(tx.getResourceBy(TYPE_EVENT_STREAM_STATE, STREAM_ID, true));
		resource.setLong(BAG_PARAMETERS, PARAM_LAST_COMMITTED_SEQUENCE, Long.parseLong(state.lastCommittedSequence()));
		resource.setLong(BAG_PARAMETERS, PARAM_MIN_AFTER, Long.parseLong(state.minAfter()));
		tx.update(resource);
	}
}
