// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;

import li.intruvia.core.model.*;
import li.intruvia.core.persistence.EventRepository;
import li.strolch.persistence.api.StrolchTransactionException;
import li.strolch.persistence.postgresql.PostgreSqlStrolchTransaction;
import org.junit.Test;

import static org.junit.Assert.*;

public class PersistenceIT {
	private final EventRepository repository = new EventRepository();
	private final SecurityEvent event = fixture();
	private final IngestReceipt receipt = new IngestReceipt("receipt-fixture", "server-a", this.event.source().eventId(),
			"a".repeat(64), this.event.id(), "1", "2026-10-28T12:00:00Z");

	@Test(timeout = 60000)
	public void persistsAllModelsAcrossRestartAndRollsBackFailedTransaction() throws Exception {
		try (var database = new DatabaseFixture()) {
			try (var app = new IntruviaApplication(database.runtime, 0)) {
				app.start();
				app.agent().getContainer().getPrivilegeHandler().runAsAgent(ctx -> {
					try (var tx = app.agent().openTx(ctx.getCertificate(), "Fixture", false).rollbackOnFailure()) {
						assertTrue(tx instanceof PostgreSqlStrolchTransaction);
						assertEquals(new EventStreamState("0", "0"), this.repository.streamState(tx));
						this.repository.addEvent(tx, this.event);
						this.repository.addReceipt(tx, this.receipt);
						this.repository.updateStreamState(tx, new EventStreamState("1", "0"));
						tx.commitOnClose();
					}
				});
			}
			// Disable creation on restart: existing schema and model must be reused.
			var config = database.runtime.resolve("config/StrolchConfiguration.xml");
			Files.writeString(config, Files.readString(config).replace("<allowSchemaCreation>true", "<allowSchemaCreation>false"));
			try (var app = new IntruviaApplication(database.runtime, 0)) {
				app.start();
				assertStored(app);
				database.rejectRollbackFixture();
				app.agent().getContainer().getPrivilegeHandler().runAsAgent(ctx -> {
					assertThrows(StrolchTransactionException.class, () -> {
						try (var tx = app.agent().openTx(ctx.getCertificate(), "FailedFixture", false).rollbackOnFailure()) {
							this.repository.addEvent(tx, fixture());
							this.repository.addReceipt(tx, new IngestReceipt("rolled-back", "server-a", UUID.randomUUID(),
									"b".repeat(64), UUID.randomUUID(), "2", "2026-10-28T12:00:00Z"));
							this.repository.updateStreamState(tx, new EventStreamState("2", "0"));
							tx.commitOnClose();
						}
					});
				});
				assertStored(app);
			}
			try (var app = new IntruviaApplication(database.runtime, 0)) {
				app.start();
				assertStored(app);
			}
		}
	}

	private void assertStored(IntruviaApplication app) throws Exception {
		app.agent().getContainer().getPrivilegeHandler().runAsAgent(ctx -> {
			try (var tx = app.agent().openTx(ctx.getCertificate(), "ReadFixture", true)) {
				assertEquals(this.event, this.repository.event(tx, this.event.id()).orElseThrow());
				assertEquals(this.receipt, this.repository.receipt(tx, this.receipt.id()).orElseThrow());
				assertEquals(new EventStreamState("1", "0"), this.repository.streamState(tx));
				assertEquals(java.util.List.of(this.event), this.repository.events(tx, 1000));
				assertTrue(this.repository.receipt(tx, "rolled-back").isEmpty());
				assertEquals("0.0.1", tx.getResourceBy("Migrations", "migrations", true).getString("currentCodeVersion"));
			}
		});
	}

	@Test(timeout = 30000)
	public void rejectsTransientAndDestructiveConfigurationBeforeStartup() throws Exception {
		try (var database = new DatabaseFixture()) {
			var path = database.runtime.resolve("config/StrolchConfiguration.xml");
			String original = Files.readString(path);
			for (String invalid : new String[]{original.replace("CACHED", "TRANSIENT"),
					original.replace("<allowSchemaDrop>false", "<allowSchemaDrop>true"),
					original.replace("<realms>defaultRealm", "<realms>anotherRealm")
							.replace("<dataStoreMode>CACHED</dataStoreMode>", "<dataStoreMode.anotherRealm>CACHED</dataStoreMode.anotherRealm>")}) {
				Files.writeString(path, invalid);
				var error = assertThrows(IllegalStateException.class, () -> new IntruviaApplication(database.runtime, 0));
				assertTrue(error.getMessage().contains("CACHED PostgreSQL"));
			}
		}
	}

	private static SecurityEvent fixture() {
		return new SecurityEvent(1, UUID.randomUUID(), "1", new SecurityEvent.Source("fail2ban", "server-a", UUID.randomUUID()),
				"network.security", "ban", Instant.parse("2026-09-28T12:00:00Z").toString(), "2026-09-28T12:00:01Z",
				new SecurityEvent.Subject("2001:db8::42"), new SecurityEvent.Observer("server-a"),
				new SecurityEvent.Attributes(new SecurityEvent.Fail2ban("sshd", null)),
				new SecurityEvent.Geo(GeoStatus.NON_PUBLIC, null, null, null, null, null, null, null, null, null, null, null));
	}
}
