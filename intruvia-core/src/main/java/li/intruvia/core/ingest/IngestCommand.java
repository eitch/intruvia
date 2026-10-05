// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.ingest;

import java.time.Instant;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

import li.intruvia.core.model.EventStreamState;
import li.intruvia.core.model.IngestReceipt;
import li.intruvia.core.model.SecurityEvent;
import li.intruvia.core.persistence.EventRepository;
import li.strolch.model.Resource;
import li.strolch.persistence.api.StrolchTransaction;
import li.strolch.service.api.Command;

import static li.intruvia.core.model.ModelConstants.*;

/** Register after the locked receipt/age check; caller owns commit and must retain the stream lock. */
public final class IngestCommand extends Command {
	private final SecurityEvent event;
	private final IngestReceipt receipt;
	private final EventStreamState state;

	public IngestCommand(StrolchTransaction tx, String instance, Fail2banEvent payload, SecurityEvent.Geo geo,
			Instant now, EventStreamState previous) {
		super(tx);
		long sequence = Math.addExact(Long.parseLong(previous.lastCommittedSequence()), 1);
		this.event = payload.toEvent(instance, UUID.randomUUID(), sequence, now, geo);
		this.receipt = new IngestReceipt(receiptId(instance, payload.eventId()), instance, payload.eventId(),
				payload.payloadDigest(), this.event.id(), this.event.sequence(), now.plus(Duration.ofDays(30)).toString());
		this.state = new EventStreamState(this.event.sequence(), previous.minAfter());
	}

	/** Delimiter is excluded by the protected identity mapping's syntax; UUID text is canonical. */
	public static String receiptId(String instance, UUID eventId) {
		if (instance == null || !instance.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
			throw new IllegalArgumentException("Invalid instance identity");
		return instance + ":" + Objects.requireNonNull(eventId);
	}

	@Override
	public void validate() {
		if (!tx().hasLock(Resource.locatorFor(TYPE_EVENT_STREAM_STATE, STREAM_ID)))
			throw new IllegalStateException("Stream state must be locked");
	}

	@Override
	public void doCommand() {
		EventRepository repository = new EventRepository();
		repository.addEvent(tx(), this.event);
		repository.addReceipt(tx(), this.receipt);
		repository.updateStreamState(tx(), this.state);
	}

	public IngestResult committedResult() {
		return new IngestResult(IngestResult.Outcome.CREATED,
				new IngestResult.Accepted(this.event.id(), this.event.sequence(), false, this.event.geo().status()));
	}
}
