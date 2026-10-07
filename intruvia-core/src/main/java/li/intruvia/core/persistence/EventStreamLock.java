// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.persistence;

import li.strolch.model.Resource;
import li.strolch.persistence.api.StrolchTransaction;
import li.strolch.utils.concurrent.ElementLockingException;
import static li.intruvia.core.model.ModelConstants.*;

/** Acquire before any stream state/event/receipt read; hold until transaction close. */
public final class EventStreamLock {
	private EventStreamLock() {}
	public static void acquire(StrolchTransaction tx) {
		if (Thread.currentThread().isInterrupted())
			throw new ElementLockingException("Stream operation interrupted");
		tx.lock(Resource.locatorFor(TYPE_EVENT_STREAM_STATE, STREAM_ID));
		// Framework acquisition can restore interruption without throwing; never proceed then.
		if (Thread.currentThread().isInterrupted())
			throw new ElementLockingException("Stream operation interrupted");
	}
}
