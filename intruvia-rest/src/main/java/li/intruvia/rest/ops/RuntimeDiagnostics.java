// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.ops;

import java.util.concurrent.atomic.AtomicLong;

/** Constant-space, saturating process-lifetime HTTP counters; no labels supplied by clients. */
public final class RuntimeDiagnostics {
	private final AtomicLong created = new AtomicLong();
	private final AtomicLong duplicates = new AtomicLong();
	private final AtomicLong failures = new AtomicLong();
	public void ingestion(int status) {
		AtomicLong counter = status == 201 ? this.created : status == 200 ? this.duplicates : this.failures;
		increment(counter);
	}
	public static void increment(AtomicLong counter) { counter.updateAndGet(value -> value == Long.MAX_VALUE ? value : value + 1); }
	public Counts counts() { return new Counts(this.created.get(), this.duplicates.get(), this.failures.get()); }
	public record Counts(long created, long duplicates, long failures) {}
}
