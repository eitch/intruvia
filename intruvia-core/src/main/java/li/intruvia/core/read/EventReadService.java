// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.read;

import li.intruvia.core.persistence.EventRepository;
import li.intruvia.core.persistence.EventStreamLock;
import li.strolch.exception.StrolchException;
import li.strolch.persistence.api.StrolchPersistenceException;
import li.strolch.persistence.api.StrolchTransactionException;
import li.strolch.service.api.AbstractService;
import li.strolch.utils.concurrent.ElementLockingException;

import static li.intruvia.core.read.EventReadResult.Outcome.*;

/**
 * One invocation through ServiceHandler; capture and materialization share ingestion's stream lock.
 */
public final class EventReadService extends AbstractService<EventReadArgument, EventReadResult> {
	public static final String READ_PRIVILEGE = "event:read";

	@Override
	public String getPrivilegeName() {
		return READ_PRIVILEGE;
	}

	@Override
	public String getPrivilegeValue() {
		return READ_PRIVILEGE;
	}

	@Override
	public EventReadArgument getArgumentInstance() {
		return new EventReadArgument(true, 0, null, 1000);
	}

	@Override
	protected EventReadResult getResultInstance() {
		return new EventReadResult();
	}

	@Override
	protected EventReadResult internalDoService(EventReadArgument arg) {
		if (arg.realm != null
				|| arg.limit < 1
				|| arg.limit > 1000
				|| arg.after < 0
				|| arg.through != null && arg.through < arg.after
				|| arg.snapshot && (arg.after != 0 || arg.through != null))
			return new EventReadResult(INVALID, null);

		try (var tx = openUserTx(true)) {
			EventStreamLock.acquire(tx);
			var state = new EventRepository().streamState(tx);
			long head = Long.parseLong(state.lastCommittedSequence());
			long minimum = Long.parseLong(state.minAfter());
			long through = arg.through == null ? head : arg.through;
			if (arg.after > head || through > head)
				return new EventReadResult(INVALID, null);
			if (!arg.snapshot && arg.after < minimum)
				return new EventReadResult(EXPIRED, null);
			var items = new EventReadSearch().window(tx, arg.snapshot ? minimum : arg.after, through, arg.limit + 1,
					arg.snapshot);
			boolean more = items.size() > arg.limit;
			if (arg.snapshot) {
				if (more)
					items = items.subList(1, items.size());
				return new EventReadResult(OK,
						new EventReadResult.Snapshot(items, state.lastCommittedSequence(), state.minAfter(), more));
			}
			if (more)
				items = items.subList(0, arg.limit);
			String next = items.isEmpty() ? Long.toString(arg.after) : items.getLast().sequence();
			return new EventReadResult(OK, new EventReadResult.Page(items, next, more, Long.toString(through)));
		} catch (StrolchException | StrolchTransactionException | StrolchPersistenceException |
		         ElementLockingException e) {
			return new EventReadResult(UNAVAILABLE, null);
		}
	}
}
