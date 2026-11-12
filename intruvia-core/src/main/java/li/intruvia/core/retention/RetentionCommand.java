// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.retention;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
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
		String nowText = now.toString();
		this.expiredReceipts = receipts.stream()
				.filter(resource -> expired(resource.getString(PARAM_EXPIRES_AT), now, nowText)).toList();
		this.hasReceiptCapacity = receipts.size() - this.expiredReceipts.size() < policy.receiptCapacity();
		var events = new RetentionSearch(privilege).resources(tx, TYPE_SECURITY_EVENT);
		int target = policy.eventCapacity() - (admission && this.hasReceiptCapacity ? 1 : 0);
		int excess = Math.max(0, events.size() - target);
		Instant cutoff = now.minus(policy.eventAge());
		String cutoffText = cutoff.toString();
		// The common admission case removes one event. Select the oldest excess candidates
		// in O(n log(excess + 1)) instead of sorting the full retained history under the lock.
		Comparator<Resource> sequenceOrder = Comparator.comparingLong(resource -> resource.getLong(BAG_ENVELOPE, PARAM_SEQUENCE));
		var oldest = new PriorityQueue<Resource>(Math.max(1, excess), sequenceOrder.reversed());
		long removedThrough = 0;
		for (Resource event : events) {
			long sequence = event.getLong(BAG_ENVELOPE, PARAM_SEQUENCE);
			if (expired(event.getString(BAG_ENVELOPE, PARAM_RECEIVED_AT), cutoff, cutoffText))
				removedThrough = Math.max(removedThrough, sequence);
			if (excess > 0) {
				if (oldest.size() < excess) oldest.add(event);
				else if (sequenceOrder.compare(event, oldest.peek()) < 0) {
					oldest.remove(); oldest.add(event);
				}
			}
		}
		if (!oldest.isEmpty())
			removedThrough = Math.max(removedThrough, oldest.peek().getLong(BAG_ENVELOPE, PARAM_SEQUENCE));
		// A reversed receive clock can expire a later sequence. Remove the complete prefix,
		// including younger observations before it, to preserve the exclusive replay cursor.
		for (Resource event : events)
			if (event.getLong(BAG_ENVELOPE, PARAM_SEQUENCE) <= removedThrough)
				this.removedEvents.add(event);
		var previous = new EventRepository().streamState(tx);
		long minimum = Math.max(Long.parseLong(previous.minAfter()), removedThrough);
		this.state = new EventStreamState(previous.lastCommittedSequence(), Long.toString(minimum));
	}

	/** Model timestamps are canonical UTC. Avoid parsing dates far from the retention boundary. */
	static boolean expired(String timestamp, Instant cutoff, String cutoffText) {
		if (timestamp.length() > 10 && cutoffText.length() > 10 && timestamp.charAt(10) == 'T' && cutoffText.charAt(10) == 'T' &&
				!timestamp.regionMatches(0, cutoffText, 0, 10))
			return timestamp.compareTo(cutoffText) < 0;
		// Same-day fractions and extended years require Instant ordering, not lexical ordering.
		return !Instant.parse(timestamp).isAfter(cutoff);
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
