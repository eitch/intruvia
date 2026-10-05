// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import java.nio.file.Files;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import li.intruvia.core.auth.MachineIdentities;
import li.intruvia.core.ingest.*;
import li.intruvia.core.model.*;
import li.intruvia.core.persistence.EventRepository;
import li.strolch.model.Resource;
import li.strolch.privilege.handler.DefaultEncryptionHandler;
import li.strolch.privilege.model.Certificate;
import li.strolch.service.api.ServiceHandler;
import org.junit.Test;

import static li.intruvia.core.ingest.IngestResult.Outcome.*;
import static li.intruvia.core.model.ModelConstants.*;
import static org.junit.Assert.*;

public class IngestionIT {
	private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
	private static final MachineIdentities IDENTITIES = new MachineIdentities(Map.of("producer", "server-a", "second", "server-b"));
	private final EventRepository repository = new EventRepository();

	@Test(timeout = 60000)
	public void concurrentDuplicatesConflictsAndDistinctEventsAreAtomic() throws Exception {
		try (var database = database(); var app = new IntruviaApplication(database.runtime, 0)) {
			app.start();
			Certificate certificate = certificate(app, "producer", "event:ingest");
			Fail2banEvent payload = payload();
			try (var executor = Executors.newFixedThreadPool(12)) {
				CountDownLatch start = new CountDownLatch(1);
				List<Future<IngestResult>> futures = new ArrayList<>();
				for (int i = 0; i < 24; i++)
					futures.add(executor.submit(() -> {
						assertTrue(start.await(10, TimeUnit.SECONDS));
						return ingest(app, certificate, payload, NOW, null);
					}));
				start.countDown();
				List<IngestResult> results = new ArrayList<>();
				for (var future : futures)
					results.add(future.get(20, TimeUnit.SECONDS));
				assertEquals(1, results.stream().filter(result -> result.outcome() == CREATED).count());
				assertEquals(23, results.stream().filter(result -> result.outcome() == DUPLICATE).count());
				UUID id = results.getFirst().accepted().id();
				for (var result : results) {
					assertTrue(result.isOk());
					assertEquals(id, result.accepted().id());
					assertEquals("1", result.accepted().sequence());
				}
				Fail2banEvent changed = new Fail2banEvent(payload.eventId(), NOW, payload.ip(), "changed", 1);
				List<Future<IngestResult>> conflicts = new ArrayList<>();
				for (int i = 0; i < 12; i++)
					conflicts.add(executor.submit(() -> ingest(app, certificate, changed, NOW, failEnrichment())));
				for (var future : conflicts)
					assertEquals(CONFLICT, future.get(20, TimeUnit.SECONDS).outcome());
				assertCounts(app, 1, 1, "1");
				List<Future<IngestResult>> distinct = new ArrayList<>();
				for (int i = 0; i < 12; i++)
					distinct.add(executor.submit(() -> ingest(app, certificate, payload(), NOW, null)));
				Set<String> sequences = new HashSet<>();
				for (var future : distinct) {
					var result = future.get(20, TimeUnit.SECONDS);
					assertEquals(CREATED, result.outcome());
					sequences.add(result.accepted().sequence());
				}
				for (int i = 2; i <= 13; i++)
					assertTrue(sequences.contains(Integer.toString(i)));
				assertCounts(app, 13, 13, "13");
			}
		}
	}

	@Test(timeout = 60000)
	public void retainedReceiptSurvivesRestartPruningAndAgeRejection() throws Exception {
		Fail2banEvent payload = payload();
		IngestResult.Accepted accepted;
		try (var database = database()) {
			try (var app = new IntruviaApplication(database.runtime, 0)) {
				app.start();
				Certificate certificate = certificate(app, "producer", "event:ingest");
				accepted = ingest(app, certificate, payload, NOW, null).accepted();
				assertNotNull(accepted);
				app.agent().getPrivilegeHandler().runAsAgent(ctx -> {
					try (var tx = app.agent().openTx(ctx.getCertificate(), "PruningFixture", false).rollbackOnFailure()) {
						tx.lock(Resource.locatorFor(TYPE_EVENT_STREAM_STATE, STREAM_ID));
						tx.remove(tx.getResourceBy(TYPE_SECURITY_EVENT, accepted.id().toString(), true));
						this.repository.updateStreamState(tx, new EventStreamState("1", "1"));
						tx.commitOnClose();
					}
				});
			}
			try (var app = new IntruviaApplication(database.runtime, 0)) {
				app.start();
				Certificate certificate = certificate(app, "producer", "event:ingest");
				var result = ingest(app, certificate, payload, NOW.plus(Duration.ofDays(8)), failEnrichment());
				assertEquals(DUPLICATE, result.outcome());
				assertEquals(accepted.id(), result.accepted().id());
				assertEquals(accepted.sequence(), result.accepted().sequence());
				assertNull(result.accepted().geoStatus());
				assertEquals(INVALID, ingest(app, certificate, payload(), NOW.plus(Duration.ofDays(8)), failEnrichment()).outcome());
				var changed = new Fail2banEvent(payload.eventId(), payload.occurredAt(), payload.ip(), "changed", null);
				assertEquals(CONFLICT, ingest(app, certificate, changed, NOW.plus(Duration.ofDays(8)), failEnrichment()).outcome());
				assertCounts(app, 0, 1, "1");
				app.agent().getPrivilegeHandler().runAsAgent(ctx -> {
					try (var tx = app.agent().openTx(ctx.getCertificate(), "ReceiptExpiry", true)) {
						var receipt = this.repository.receipt(tx, IngestCommand.receiptId("server-a", payload.eventId())).orElseThrow();
						assertEquals(NOW.plus(Duration.ofDays(30)).toString(), receipt.expiresAt());
						assertEquals("1", this.repository.streamState(tx).minAfter());
					}
				});
			}
		}
	}

	@Test(timeout = 60000)
	public void databaseFailureRollsBackEventReceiptAndSequence() throws Exception {
		try (var database = database()) {
			Fail2banEvent payload = payload();
			try (var app = new IntruviaApplication(database.runtime, 0)) {
				app.start();
				Certificate certificate = certificate(app, "producer", "event:ingest");
				database.rejectReceipt(IngestCommand.receiptId("server-a", payload.eventId()));
				IngestResult failed = ingest(app, certificate, payload, NOW, null);
				assertFalse(failed.isOk());
				assertEquals(UNAVAILABLE, failed.outcome());
				assertNull(failed.accepted());
				assertNull(failed.getThrowable());
				assertCounts(app, 0, 0, "0");
			}
			try (var app = new IntruviaApplication(database.runtime, 0)) {
				app.start();
				assertCounts(app, 0, 0, "0");
				database.allowReceipt();
				var result = ingest(app, certificate(app, "producer", "event:ingest"), payload, NOW, null);
				assertEquals(CREATED, result.outcome());
				assertEquals("1", result.accepted().sequence());
				assertCounts(app, 1, 1, "1");
			}
		}
	}

	@Test(timeout = 60000)
	public void enrichmentDoesNotHoldStreamLockAndCommitRechecksReceipt() throws Exception {
		try (var database = database(); var app = new IntruviaApplication(database.runtime, 0);
				var executor = Executors.newSingleThreadExecutor()) {
			app.start();
			Certificate certificate = certificate(app, "producer", "event:ingest");
			Fail2banEvent payload = payload();
			CountDownLatch enriching = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			AtomicInteger calls = new AtomicInteger();
			var pending = executor.submit(() -> ingest(app, certificate, payload, NOW, ip -> {
				calls.incrementAndGet();
				enriching.countDown();
				await(release);
				return geo(GeoStatus.ERROR);
			}));
			try {
				assertTrue(enriching.await(10, TimeUnit.SECONDS));
				// A second ingestion can commit while the first lookup is suspended.
				var winner = ingest(app, certificate, payload, NOW, ip -> geo(GeoStatus.UNAVAILABLE));
				assertEquals(CREATED, winner.outcome());
				assertEquals(GeoStatus.UNAVAILABLE, winner.accepted().geoStatus());
				release.countDown();
				var duplicate = pending.get(10, TimeUnit.SECONDS);
				assertEquals(DUPLICATE, duplicate.outcome());
				assertEquals(winner.accepted().id(), duplicate.accepted().id());
				assertEquals(1, calls.get());
				assertCounts(app, 1, 1, "1");
			} finally {
				release.countDown();
			}
		}
	}

	@Test(timeout = 60000)
	public void certificateScopesAndIdentityAreUsedWithoutElevation() throws Exception {
		try (var database = database(); var app = new IntruviaApplication(database.runtime, 0)) {
			app.start();
			Fail2banEvent payload = payload();
			Certificate producer = certificate(app, "producer", "event:ingest");
			Certificate second = certificate(app, "second", "event:ingest");
			assertEquals(Set.of("event:ingest"), app.agent().getPrivilegeHandler().validate(producer).getPrivileges().keySet());
			assertEquals(CREATED, ingest(app, producer, payload, NOW, null).outcome());
			assertEquals(CREATED, ingest(app, second, payload, NOW, ip -> geo(GeoStatus.ERROR)).outcome());
			assertFalse(ingest(app, certificate(app, "producer", "event:read"), payload(), NOW, failEnrichment()).isOk());
			assertEquals(FORBIDDEN, ingest(app, certificate(app, "unmapped", "event:ingest"), payload(), NOW, failEnrichment()).outcome());
			IngestArgument wrongRealm = new IngestArgument(payload());
			wrongRealm.realm = "defaultRealm";
			assertEquals(INVALID, app.agent().getContainer().getComponent(ServiceHandler.class)
					.doService(producer, new IngestService(IDENTITIES), wrongRealm).outcome());
			assertCounts(app, 2, 2, "2");
			app.agent().getPrivilegeHandler().runAsAgent(ctx -> {
				try (var tx = app.agent().openTx(ctx.getCertificate(), "IdentityFixture", true)) {
					var events = this.repository.events(tx, 1000);
					assertEquals(List.of("server-a", "server-b"), events.stream().map(event -> event.source().instanceId()).toList());
					assertEquals(NOW.toString(), events.getFirst().receivedAt());
					assertEquals(GeoStatus.ERROR, events.getLast().geo().status());
				}
			});
		}
	}

	@Test(timeout = 60000)
	public void interruptedAdmissionAndAgeChangeDuringEnrichmentDoNotWrite() throws Exception {
		try (var database = database(); var app = new IntruviaApplication(database.runtime, 0)) {
			app.start();
			Certificate certificate = certificate(app, "producer", "event:ingest");
			Thread.currentThread().interrupt();
			try {
				assertEquals(UNAVAILABLE, ingest(app, certificate, payload(), NOW, failEnrichment()).outcome());
				assertTrue(Thread.currentThread().isInterrupted());
			} finally {
				Thread.interrupted();
			}
			var time = new java.util.concurrent.atomic.AtomicReference<>(NOW);
			Clock clock = new Clock() {
				@Override public ZoneId getZone() { return ZoneOffset.UTC; }
				@Override public Clock withZone(ZoneId zone) { return this; }
				@Override public Instant instant() { return time.get(); }
			};
			var service = new IngestService(IDENTITIES, clock, EventAgePolicy.DEFAULT, ip -> {
				time.set(NOW.plus(Duration.ofDays(8)));
				return geo(GeoStatus.ERROR);
			});
			var result = app.agent().getContainer().getComponent(ServiceHandler.class)
					.doService(certificate, service, new IngestArgument(payload()));
			assertEquals(INVALID, result.outcome());
			assertCounts(app, 0, 0, "0");
			assertEquals(CREATED, ingest(app, certificate, payload(), NOW, null).outcome());
			assertCounts(app, 1, 1, "1");
		}
	}

	private static IngestResult ingest(IntruviaApplication app, Certificate certificate, Fail2banEvent payload,
			Instant now, Function<String, SecurityEvent.Geo> enrichment) {
		return app.agent().getContainer().getComponent(ServiceHandler.class).doService(certificate,
				new IngestService(IDENTITIES, Clock.fixed(now, ZoneOffset.UTC), EventAgePolicy.DEFAULT, enrichment), new IngestArgument(payload));
	}

	private void assertCounts(IntruviaApplication app, int events, int receipts, String sequence) throws Exception {
		app.agent().getPrivilegeHandler().runAsAgent(ctx -> {
			try (var tx = app.agent().openTx(ctx.getCertificate(), "Counts", true)) {
				tx.lock(Resource.locatorFor(TYPE_EVENT_STREAM_STATE, STREAM_ID));
				assertEquals(events, tx.streamResources(TYPE_SECURITY_EVENT).count());
				assertEquals(receipts, tx.streamResources(TYPE_INGEST_RECEIPT).count());
				assertEquals(sequence, this.repository.streamState(tx).lastCommittedSequence());
			}
		});
	}

	private static Fail2banEvent payload() {
		return new Fail2banEvent(UUID.randomUUID(), NOW, "2001:db8::1", "sshd", null);
	}

	private static SecurityEvent.Geo geo(GeoStatus status) {
		return new SecurityEvent.Geo(status, null, null, null, null, null, null, null, null, null, null, NOW.toString());
	}

	private static Function<String, SecurityEvent.Geo> failEnrichment() {
		return ip -> { throw new AssertionError("An accepted retry, conflict or invalid age must not enrich"); };
	}

	private static void await(CountDownLatch latch) {
		try {
			assertTrue(latch.await(10, TimeUnit.SECONDS));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static Certificate certificate(IntruviaApplication app, String username, String privilege) {
		var handler = app.agent().getPrivilegeHandler().getPrivilegeHandler();
		var owner = handler.authenticate(username, "test-password".toCharArray(), false);
		String token = handler.createPersonalAccessToken(owner, "fixture", ZonedDateTime.now().minusMinutes(1),
				ZonedDateTime.now().plusMinutes(5), Set.of(), Set.of(privilege));
		return handler.authenticatePersonalAccessToken(token, "ingestion-test");
	}

	private static DatabaseFixture database() throws Exception {
		var database = new DatabaseFixture();
		var encryption = new DefaultEncryptionHandler();
		encryption.initialize(Map.of("hashAlgorithm", "PBKDF2WithHmacSHA512", "hashIterations", "10000", "hashKeyLength", "256"));
		String password = encryption.hashPassword("test-password".toCharArray(), encryption.nextSalt()).toString();
		var users = database.runtime.resolve("config/PrivilegeUsers.xml");
		String xml = Files.readString(users);
		for (String username : List.of("producer", "second", "unmapped"))
			xml = xml.replace("</Users>", """
					<User userId="%s" username="%s" password="%s"><Firstname>Test</Firstname><Lastname>Producer</Lastname>
					<Locale>en-GB</Locale><State>ENABLED</State><Roles><Role>Producer</Role></Roles></User></Users>
					""".formatted(username, username, password));
		Files.writeString(users, xml);
		var roles = database.runtime.resolve("config/PrivilegeRoles.xml");
		Files.writeString(roles, Files.readString(roles).replace("</Roles>", """
				<Role name="Producer">
				<Privilege name="PrivilegePersonalAccessToken" policy="DefaultPrivilege"><AllAllowed>true</AllAllowed></Privilege>
				<Privilege name="event:ingest" policy="DefaultPrivilege"><AllAllowed>true</AllAllowed></Privilege>
				<Privilege name="event:read" policy="DefaultPrivilege"><AllAllowed>true</AllAllowed></Privilege>
				</Role></Roles>
				"""));
		return database;
	}
}
