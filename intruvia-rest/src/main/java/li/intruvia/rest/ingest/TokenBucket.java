// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.ingest;

import java.util.function.LongSupplier;

/** Monotonic, synchronized admission; returns whole Retry-After seconds, or zero on acceptance. */
public final class TokenBucket {
	private final int rate;
	private final int capacity;
	private final LongSupplier clock;
	private double tokens;
	private long updated;

	public TokenBucket(int rate, int capacity) { this(rate, capacity, System::nanoTime); }

	TokenBucket(int rate, int capacity, LongSupplier clock) {
		if (rate <= 0 || capacity <= 0)
			throw new IllegalArgumentException("Positive token bucket limits required");
		this.rate = rate;
		this.capacity = capacity;
		this.clock = clock;
		this.tokens = capacity;
		this.updated = clock.getAsLong();
	}

	public synchronized int acquire() {
		long now = this.clock.getAsLong();
		this.tokens = Math.min(this.capacity, this.tokens + Math.max(0, now - this.updated) / 1_000_000_000.0 * this.rate);
		this.updated = now;
		if (this.tokens >= 1) {
			this.tokens--;
			return 0;
		}
		return Math.max(1, (int) Math.ceil((1 - this.tokens) / this.rate));
	}
}
