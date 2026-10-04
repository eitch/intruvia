// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.probe;

import li.strolch.privilege.base.AccessDeniedException;
import li.strolch.privilege.handler.DefaultEncryptionHandler;
import li.strolch.privilege.handler.PrivilegeHandler;
import li.strolch.privilege.handler.XmlPersistenceHandler;
import li.strolch.privilege.helper.PrivilegeInitializer;
import li.strolch.privilege.model.Certificate;
import li.strolch.privilege.model.internal.PersonalAccessToken;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Isolated dependency probe; never loads production credentials or installs a replacement verifier. */
public class PatRevocationTest {
	private ScheduledExecutorService scheduler;
	private Path runtime;
	private PrivilegeHandler handler;
	private Certificate owner;

	@Before
	public void setup() throws Exception {
		this.runtime = Files.createTempDirectory(Path.of("target"), "pat-revocation-");
		this.scheduler = Executors.newScheduledThreadPool(1);
		var encryption = new DefaultEncryptionHandler();
		encryption.initialize(Map.of("hashAlgorithm", "PBKDF2WithHmacSHA512", "hashIterations", "10000", "hashKeyLength", "256"));
		String password = encryption.hashPassword("probe-password".toCharArray(), encryption.nextSalt()).toString();
		Files.writeString(this.runtime.resolve("PrivilegeUsers.xml"), """
				<Users><User userId="1" username="producer" password="%s">
				<Firstname>Probe</Firstname><Lastname>Producer</Lastname><Locale>en-GB</Locale>
				<State>ENABLED</State><Roles><Role>Producer</Role></Roles>
				</User></Users>
				""".formatted(password));
		Files.writeString(this.runtime.resolve("PrivilegeRoles.xml"), """
				<Roles><Role name="Producer">
				<Privilege name="PrivilegePersonalAccessToken" policy="DefaultPrivilege"><AllAllowed>true</AllAllowed></Privilege>
				<Privilege name="event:ingest" policy="DefaultPrivilege"><AllAllowed>true</AllAllowed></Privilege>
				</Role></Roles>
				""");
		Files.writeString(this.runtime.resolve("PrivilegeConfig.xml"), """
				<Privilege><Container><Parameters>
				<Parameter name="secretKey" value="%s"/><Parameter name="secretSalt" value="%s"/>
				<Parameter name="autoPersistOnUserChangesData" value="false"/>
				</Parameters>
				<EncryptionHandler class="li.strolch.privilege.handler.DefaultEncryptionHandler">
				<Parameters><Parameter name="hashAlgorithm" value="PBKDF2WithHmacSHA512"/>
				<Parameter name="hashIterations" value="10000"/><Parameter name="hashKeyLength" value="256"/></Parameters>
				</EncryptionHandler>
				<PersistenceHandler class="li.intruvia.probe.PatRevocationTest$PausingPersistenceHandler"/>
				<UserChallengeHandler class="li.strolch.privilege.handler.ConsoleUserChallengeHandler"/>
				</Container><Policies>
				<Policy name="DefaultPrivilege" class="li.strolch.privilege.policy.DefaultPrivilege"/>
				</Policies></Privilege>
				""".formatted(encryption.nextToken(), encryption.nextToken()));
		this.handler = new PrivilegeInitializer(false, this.scheduler)
				.initializeFromXml(this.runtime.resolve("PrivilegeConfig.xml").toFile());
		this.owner = this.handler.authenticate("producer", "probe-password".toCharArray(), false);
	}

	@After
	public void cleanup() throws Exception {
		if (this.scheduler != null) {
			this.scheduler.shutdownNow();
			assertTrue(this.scheduler.awaitTermination(10, TimeUnit.SECONDS));
		}
		if (this.runtime != null) {
			try (var paths = Files.walk(this.runtime)) {
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}

	@Test
	public void sequentialCachedRevocationRejectsToken() {
		String token = issue();
		Certificate first = this.handler.authenticatePersonalAccessToken(token, "probe");
		Certificate cached = this.handler.authenticatePersonalAccessToken(token, "probe");
		assertEquals(first.getSessionId(), cached.getSessionId());
		assertTrue(first.getUsage().isApi());
		assertEquals(Set.of("event:ingest"), this.handler.validate(first).getPrivileges().keySet());
		this.handler.removePersonalAccessToken(this.owner, token.split(":", 2)[0]);
		assertThrows(AccessDeniedException.class, () -> this.handler.authenticatePersonalAccessToken(token, "probe"));
	}

	@Test
	public void concurrentAuthenticationMustNotRestoreRevokedToken() throws Exception {
		String token = issue();
		String tokenId = token.split(":", 2)[0];
		var persistence = PausingPersistenceHandler.instance;
		try (var worker = Executors.newFixedThreadPool(2)) {
			var inFlight = worker.submit(() -> {
				persistence.pauseThread = Thread.currentThread();
				try {
					return this.handler.authenticatePersonalAccessToken(token, "probe");
				} catch (AccessDeniedException expected) {
					return null;
				}
			});
			assertTrue("Authentication did not reach persistence lookup", persistence.loaded.await(10, TimeUnit.SECONDS));
			CountDownLatch revoking = new CountDownLatch(1);
			var revocation = worker.submit(() -> {
				revoking.countDown();
				this.handler.removePersonalAccessToken(this.owner, tokenId);
			});
			try {
				assertTrue(revoking.await(10, TimeUnit.SECONDS));
				// Permit either serialized or overlapping implementations; never hold the read while waiting for a lock.
			} finally {
				persistence.resume.countDown();
			}
			inFlight.get(10, TimeUnit.SECONDS);
			revocation.get(10, TimeUnit.SECONDS);
			assertTrue("Revocation must have completed", this.handler.getPersonalAccessTokens(this.owner).isEmpty());
			// The original request may have overlapped revocation. A NEW request must fail afterwards.
			assertThrows("A fresh request accepted a token after completed revocation and in-flight authentication",
					AccessDeniedException.class, () -> this.handler.authenticatePersonalAccessToken(token, "probe"));
		}
	}

	private String issue() {
		return this.handler.createPersonalAccessToken(this.owner, "probe", ZonedDateTime.now().minusMinutes(1),
				ZonedDateTime.now().plusHours(1), Set.of(), Set.of("event:ingest"));
	}

	/** Only controls scheduling after a real XML-store read; cryptography and lifecycle stay in Strolch. */
	public static class PausingPersistenceHandler extends XmlPersistenceHandler {
		static PausingPersistenceHandler instance;
		final CountDownLatch loaded = new CountDownLatch(1);
		final CountDownLatch resume = new CountDownLatch(1);
		volatile Thread pauseThread;

		public PausingPersistenceHandler() {
			instance = this;
		}

		@Override
		public PersonalAccessToken getAccessToken(String tokenId) {
			PersonalAccessToken token = super.getAccessToken(tokenId);
			if (Thread.currentThread() == this.pauseThread) {
				this.pauseThread = null;
				this.loaded.countDown();
				try {
					if (!this.resume.await(10, TimeUnit.SECONDS))
						throw new IllegalStateException("Probe scheduling timed out");
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("Probe interrupted", e);
				}
			}
			return token;
		}
	}
}
