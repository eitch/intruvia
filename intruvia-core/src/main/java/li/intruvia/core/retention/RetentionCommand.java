// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.retention;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import li.intruvia.core.model.EventStreamState;
import li.intruvia.core.persistence.EventRepository;
import li.strolch.model.Resource;
import li.strolch.persistence.api.StrolchTransaction;
import li.strolch.service.api.Command;
import static li.intruvia.core.model.ModelConstants.*;

/** Plans under the stream lock; executes with any new event in the same rollback-capable transaction. */
public final class RetentionCommand extends Command {
	private final List<Resource> expiredReceipts;
	private final List<Resource> removedEvents = new ArrayList<>();
	private final EventStreamState state;
	private final boolean hasReceiptCapacity;

	public RetentionCommand(StrolchTransaction tx, RetentionPolicy policy, Instant now, String privilege, boolean admission) {
		super(tx);
		validate();
		var receipts = new RetentionSearch(privilege).resources(tx, TYPE_INGEST_RECEIPT);
		this.expiredReceipts = receipts.stream()
				.filter(resource -> !Instant.parse(resource.getString(PARAM_EXPIRES_AT)).isAfter(now)).toList();
		this.hasReceiptCapacity = receipts.size() - this.expiredReceipts.size() < policy.receiptCapacity();
		var events = new RetentionSearch(privilege).events(tx);
		int target = policy.eventCapacity() - (admission && this.hasReceiptCapacity ? 1 : 0);
		int countPrefix = Math.max(0, events.size() - target);
		Instant cutoff = now.minus(policy.eventAge());
		// A clock reversal can place old timestamps later in the log. Remove through the last expired event
		// to preserve the contiguous-prefix invariant; newer events within that prefix are also removed.
		int agePrefix = 0;
		for (int index = 0; index < events.size(); index++)
			if (!Instant.parse(events.get(index).getString(BAG_ENVELOPE, PARAM_RECEIVED_AT)).isAfter(cutoff))
				agePrefix = index + 1;
		int remove = Math.max(countPrefix, agePrefix);
		this.removedEvents.addAll(events.subList(0, remove));
		var previous = new EventRepository().streamState(tx);
		long minimum = Long.parseLong(previous.minAfter());
		if (remove > 0)
			minimum = Math.max(minimum, events.get(remove - 1).getLong(BAG_ENVELOPE, PARAM_SEQUENCE));
		this.state = new EventStreamState(previous.lastCommittedSequence(), Long.toString(minimum));
	}

	public boolean hasReceiptCapacity() { return this.hasReceiptCapacity; }
	public EventStreamState state() { return this.state; }

	@Override public void validate() {
		if (!tx().hasLock(Resource.locatorFor(TYPE_EVENT_STREAM_STATE, STREAM_ID)))
			throw new IllegalStateException("Retention requires stream lock");
	}

	@Override public void doCommand() {
		for (Resource receipt : this.expiredReceipts)
			tx().remove(tx().readLock(receipt));
		for (Resource event : this.removedEvents)
			tx().remove(tx().readLock(event));
		new EventRepository().updateStreamState(tx(), this.state);
	}
}
