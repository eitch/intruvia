// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.ingest;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdmissionTest {
	@Test
	public void monotonicRefillAndConcurrentBurstAreBounded() throws Exception {
		AtomicLong time = new AtomicLong();
		var bucket = new TokenBucket(10, 100, time::get);
		try (var executor = Executors.newFixedThreadPool(8)) {
			var requests = new ArrayList<Future<Integer>>();
			for (int i = 0; i < 200; i++) requests.add(executor.submit(bucket::acquire));
			int accepted = 0;
			for (var request : requests) if (request.get() == 0) accepted++;
			assertEquals(100, accepted);
		}
		time.set(99_000_000);
		assertEquals(1, bucket.acquire());
		time.set(100_000_000);
		assertEquals(0, bucket.acquire());
		assertEquals(1, bucket.acquire());
		time.set(1_000_000_000_000L);
		for (int i = 0; i < 100; i++) assertEquals(0, bucket.acquire());
		assertEquals(1, bucket.acquire());
	}

	@Test
	public void unexpectedErrorsNeverExposeDiagnosticValues() {
		var mapper = new IngestExceptionMapper();
		var response = mapper.toResponse(new IllegalStateException("sensitive-token-and-payload"));
		assertEquals(503, response.getStatus());
		assertFalse(response.getEntity().toString().contains("sensitive-token-and-payload"));
		assertTrue(response.getEntity().toString().contains("requestId"));
		response = mapper.toResponse(new jakarta.ws.rs.BadRequestException("sensitive-media-value"));
		assertEquals(400, response.getStatus());
		assertFalse(response.getEntity().toString().contains("sensitive-media-value"));
	}

	@Test
	public void externalConfigurationDefaultsAndInvalidBounds() throws Exception {
		var file = Files.createTempFile("ingestion", ".properties");
		try {
			var defaults = IngestionLimits.load(file);
			assertEquals(16384, defaults.bodyBytes());
			assertEquals(10, defaults.eventsPerSecond());
			assertEquals(100, defaults.burst());
			for (String invalid : new String[]{"bodyBytes=0", "bodyBytes=1048577", "burst=-1", "eventsPerSecond=NaN",
					"connections=0", "unknown=1", "maximumAgeSeconds=-1", "idleSeconds=301"}) {
				Files.writeString(file, invalid);
				assertThrows(IllegalArgumentException.class, () -> IngestionLimits.load(file));
			}
		} finally {
			Files.delete(file);
		}
	}
}
