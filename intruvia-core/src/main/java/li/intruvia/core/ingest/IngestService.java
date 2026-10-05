// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.ingest;

import java.time.Clock;
import java.util.Objects;
import java.util.function.Function;

import li.intruvia.core.auth.MachineIdentities;
import li.intruvia.core.geo.GeoIpComponent;
import li.intruvia.core.model.SecurityEvent;
import li.intruvia.core.persistence.EventRepository;
import li.strolch.exception.StrolchException;
import li.strolch.model.Resource;
import li.strolch.persistence.api.StrolchTransaction;
import li.strolch.persistence.api.StrolchTransactionException;
import li.strolch.persistence.api.StrolchPersistenceException;
import li.strolch.service.api.AbstractService;
import li.strolch.utils.concurrent.ElementLockingException;

import static li.intruvia.core.model.ModelConstants.*;
import static li.intruvia.core.ingest.IngestResult.Outcome.*;

/** One instance per invocation, executed through the framework ServiceHandler with the caller's certificate. */
public final class IngestService extends AbstractService<IngestArgument, IngestResult> {
	public static final String INGEST_PRIVILEGE = "event:ingest";
	private final MachineIdentities identities;
	private final Clock clock;
	private final EventAgePolicy agePolicy;
	private final Function<String, SecurityEvent.Geo> enrichment;
	private final EventRepository repository = new EventRepository();

	public IngestService(MachineIdentities identities) {
		this(identities, Clock.systemUTC(), EventAgePolicy.DEFAULT, null);
	}

	/** Trusted application dependencies, never request values. Null enrichment selects the managed GeoIP component. */
	public IngestService(MachineIdentities identities, Clock clock, EventAgePolicy agePolicy,
			Function<String, SecurityEvent.Geo> enrichment) {
		this.identities = Objects.requireNonNull(identities);
		this.clock = Objects.requireNonNull(clock);
		this.agePolicy = Objects.requireNonNull(agePolicy);
		this.enrichment = enrichment;
	}

	@Override public String getPrivilegeName() { return INGEST_PRIVILEGE; }
	@Override public String getPrivilegeValue() { return INGEST_PRIVILEGE; }
	@Override public IngestArgument getArgumentInstance() { return new IngestArgument(); }
	@Override protected IngestResult getResultInstance() { return new IngestResult(); }

	@Override
	protected IngestResult internalDoService(IngestArgument arg) {
		String instance = this.identities.instances().get(getCertificate().getUsername());
		if (instance == null)
			return new IngestResult(FORBIDDEN, null);
		// The single configured realm belongs to the authenticated user, never the request.
		if (arg.event == null || arg.realm != null)
			return new IngestResult(INVALID, null);
		try {
			try (var tx = openUserTx(true)) {
				lockStream(tx);
				IngestResult duplicate = existing(tx, instance, arg.event);
				if (duplicate != null)
					return duplicate;
				this.agePolicy.validate(arg.event.occurredAt(), this.clock.instant());
			}
			SecurityEvent.Geo geo = this.enrichment == null ? getComponent(GeoIpComponent.class).enrich(arg.event.ip()) :
					this.enrichment.apply(arg.event.ip());
			IngestCommand command;
			try (var tx = openUserTx().rollbackOnFailure()) {
				lockStream(tx);
				IngestResult duplicate = existing(tx, instance, arg.event);
				if (duplicate != null)
					return duplicate;
				var now = this.clock.instant();
				this.agePolicy.validate(arg.event.occurredAt(), now);
				command = new IngestCommand(tx, instance, arg.event, geo, now, this.repository.streamState(tx));
				tx.addCommand(command);
				tx.commitOnClose();
			}
			// No success or publication may escape until transaction close has completed.
			return command.committedResult();
		} catch (InvalidEventException e) {
			return new IngestResult(INVALID, null);
		} catch (StrolchException | StrolchTransactionException | StrolchPersistenceException |
				ArithmeticException | ElementLockingException e) {
			// Fixed failure contract; do not propagate persistence details into service logs or HTTP responses.
			return new IngestResult(UNAVAILABLE, null);
		}
	}

	private static void lockStream(StrolchTransaction tx) {
		if (Thread.currentThread().isInterrupted())
			throw new ElementLockingException("Ingestion interrupted");
		tx.lock(Resource.locatorFor(TYPE_EVENT_STREAM_STATE, STREAM_ID));
		// Local framework lock acquisition restores interruption without throwing. Never proceed in that case.
		if (Thread.currentThread().isInterrupted())
			throw new ElementLockingException("Ingestion interrupted");
	}

	private IngestResult existing(StrolchTransaction tx, String instance, Fail2banEvent payload) {
		var receipt = this.repository.receipt(tx, IngestCommand.receiptId(instance, payload.eventId()));
		if (receipt.isEmpty())
			return null;
		var value = receipt.get();
		if (!value.payloadDigest().equals(payload.payloadDigest()))
			return new IngestResult(CONFLICT, null);
		return new IngestResult(DUPLICATE, new IngestResult.Accepted(value.eventId(), value.sequence(), true, null));
	}
}
