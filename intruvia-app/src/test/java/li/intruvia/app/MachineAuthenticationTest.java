// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import com.google.gson.JsonParser;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import li.intruvia.core.auth.MachineIdentities;
import li.intruvia.rest.IntruviaRestApplication;
import li.intruvia.rest.auth.MachineAuthenticationFilter.Identity;
import li.intruvia.rest.auth.MachineIngest;
import li.strolch.privilege.handler.DefaultEncryptionHandler;
import li.strolch.privilege.handler.PrivilegeHandler;
import li.strolch.privilege.helper.PrivilegeInitializer;
import li.strolch.privilege.model.Certificate;
import li.strolch.privilege.model.UserState;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.glassfish.jersey.servlet.ServletContainer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class MachineAuthenticationTest {
	private ScheduledExecutorService scheduler;
	private java.nio.file.Path runtime;
	private PrivilegeHandler handler;
	private Certificate owner;

	@Path("probe")
	public static class Probe {
		@GET
		@MachineIngest
		public String identity(@Context SecurityContext context) {
			assertTrue(context.isUserInRole("event:ingest"));
			assertFalse(context.isUserInRole("event:read"));
			Identity identity = (Identity) context.getUserPrincipal();
			assertTrue(identity.certificate().getUsage().isApi());
			return identity.instanceId() + ":" + identity.certificate().getUsername();
		}
	}
	@Before
	public void setup() throws Exception {
		this.runtime = Files.createTempDirectory(java.nio.file.Path.of("target"), "pat-http-");
		this.scheduler = Executors.newScheduledThreadPool(1);
		var encryption = new DefaultEncryptionHandler();
		encryption.initialize(Map.of("hashAlgorithm", "PBKDF2WithHmacSHA512", "hashIterations", "10000", "hashKeyLength", "256"));
		String password = encryption.hashPassword("probe-password".toCharArray(), encryption.nextSalt()).toString();
		StringBuilder users = new StringBuilder("<Users>");
		for (String username : new String[]{"producer", "second", "unmapped"})
			users.append("""
					<User userId="%s" username="%s" password="%s">
					<Firstname>Probe</Firstname><Lastname>Producer</Lastname><Locale>en-GB</Locale>
					<State>ENABLED</State><Roles><Role>Producer</Role></Roles></User>
					""".formatted(username, username, password));
		Files.writeString(this.runtime.resolve("PrivilegeUsers.xml"), users.append("</Users>").toString());
		Files.writeString(this.runtime.resolve("PrivilegeRoles.xml"), """
				<Roles><Role name="Producer">
				<Privilege name="PrivilegePersonalAccessToken" policy="DefaultPrivilege"><AllAllowed>true</AllAllowed></Privilege>
				<Privilege name="PrivilegeSetUserState" policy="UserAccessPrivilege"><AllAllowed>true</AllAllowed></Privilege>
				<Privilege name="event:read" policy="DefaultPrivilege"><AllAllowed>true</AllAllowed></Privilege>
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
				<PersistenceHandler class="li.strolch.privilege.handler.XmlPersistenceHandler"/>
				<UserChallengeHandler class="li.strolch.privilege.handler.ConsoleUserChallengeHandler"/>
				</Container><Policies>
				<Policy name="DefaultPrivilege" class="li.strolch.privilege.policy.DefaultPrivilege"/>
				<Policy name="UserAccessPrivilege" class="li.strolch.privilege.policy.UserAccessPrivilege"/>
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
				for (java.nio.file.Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}

	@Test
	public void authenticatesRealPatsAndEnforcesLifecycleOverHttp() throws Exception {
		String old = issue(this.owner, Set.of("event:ingest"), -1, 60);
		String replacement = issue(this.owner, Set.of("event:ingest"), -1, 60);
		String denied = issue(this.owner, Set.of("event:read"), -1, 60);
		String broad = issue(this.owner, Set.of(), -1, 60);
		Certificate second = this.handler.authenticate("second", "probe-password".toCharArray(), false);
		Certificate unmapped = this.handler.authenticate("unmapped", "probe-password".toCharArray(), false);
		String secondToken = issue(second, Set.of("event:ingest"), -1, 60);
		String unmappedToken = issue(unmapped, Set.of("event:ingest"), -1, 60);
		String expired = issue(this.owner, Set.of("event:ingest"), -2, -1);
		String future = issue(this.owner, Set.of("event:ingest"), 1, 2);
		var mapping = new MachineIdentities(Map.of("producer", "server-a", "second", "server-b"));
		Server server = new Server();
		ServerConnector connector = new ServerConnector(server);
		connector.setHost("127.0.0.1");
		connector.setPort(0);
		server.addConnector(connector);
		var context = new ServletContextHandler();
		context.setContextPath("/");
		var app = new IntruviaRestApplication(() -> this.handler, mapping).register(Probe.class);
		context.addServlet(new ServletHolder(new ServletContainer(app)), "/*");
		server.setHandler(context);
		try {
			server.start();
			URI uri = URI.create("http://127.0.0.1:" + connector.getLocalPort() + "/probe?instanceId=spoofed");
			try (HttpClient client = HttpClient.newHttpClient()) {
				check(client, uri, null, 401, null);
				for (String invalid : new String[]{"Basic " + old, "Bearer invalid", "Bearer :", "Bearer " + old + ":extra",
						"Bearer " + old.substring(0, old.indexOf(':') + 1) + "invalid", "Bearer " + expired, "Bearer " + future,
						"Bearer " + this.owner.getSessionId(), "Bearer " + "a".repeat(513)})
					check(client, uri, invalid, 401, null);
				check(client, uri, "Bearer " + denied, 403, null);
				check(client, uri, "Bearer " + broad, 403, null);
				check(client, uri, "Bearer " + unmappedToken, 403, null);
				check(client, uri, "Bearer " + old, 200, "server-a:producer");
				check(client, uri, "Bearer " + old, 200, "server-a:producer");
				check(client, uri, "Bearer " + old.split(":", 2)[0] + ":wrong-cached-secret", 401, null);
				check(client, uri, "bearer " + replacement, 200, "server-a:producer");
				check(client, uri, "Bearer " + secondToken, 200, "server-b:second");
				var certificate = this.handler.authenticatePersonalAccessToken(old, "probe");
				assertEquals(Set.of("event:ingest"), this.handler.validate(certificate).getPrivileges().keySet());
				var duplicate = HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + old)
						.header("Authorization", "Bearer " + replacement).build();
				assertEquals(401, client.send(duplicate, HttpResponse.BodyHandlers.ofString()).statusCode());
				var session = HttpRequest.newBuilder(uri).header("Cookie", "JSESSIONID=" + this.owner.getSessionId()).build();
				assertEquals(401, client.send(session, HttpResponse.BodyHandlers.ofString()).statusCode());
				this.handler.removePersonalAccessToken(this.owner, old.split(":", 2)[0]);
				check(client, uri, "Bearer " + old, 401, null);
				check(client, uri, "Bearer " + replacement, 200, "server-a:producer");
				ZonedDateTime expiry = ZonedDateTime.now().plusSeconds(2);
				String shortLived = this.handler.createPersonalAccessToken(this.owner, "expiry", ZonedDateTime.now().minusMinutes(1),
						expiry, Set.of(), Set.of("event:ingest"));
				check(client, uri, "Bearer " + shortLived, 200, "server-a:producer");
				while (!ZonedDateTime.now().isAfter(expiry))
					Thread.sleep(20);
				check(client, uri, "Bearer " + shortLived, 401, null);
				this.handler.setUserState(this.owner, "second", UserState.DISABLED);
				check(client, uri, "Bearer " + secondToken, 401, null);
			}
		} finally {
			server.stop();
			server.destroy();
		}
	}

	@Test
	public void validatesProtectedIdentityMapping() throws Exception {
		var file = this.runtime.resolve("machine-identities.conf");
		assertTrue(MachineIdentities.load(file).instances().isEmpty());
		Files.writeString(file, "producer=server-a\nsecond=server-b\n");
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
		assertEquals("server-a", MachineIdentities.load(file).instances().get("producer"));
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
		assertThrows(IOException.class, () -> MachineIdentities.load(file));
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
		for (String invalid : new String[]{"producer=one\nproducer=two", "producer=one\nsecond=one", "bad", "=server", "user=bad id"}) {
			Files.writeString(file, invalid);
			assertThrows(IOException.class, () -> MachineIdentities.load(file));
		}
		var link = this.runtime.resolve("link");
		Files.createSymbolicLink(link, file.getFileName());
		assertThrows(IOException.class, () -> MachineIdentities.load(link));
	}

	private String issue(Certificate owner, Set<String> privileges, int fromMinutes, int toMinutes) {
		return this.handler.createPersonalAccessToken(owner, "display-name-is-not-identity", ZonedDateTime.now().plusMinutes(fromMinutes),
				ZonedDateTime.now().plusMinutes(toMinutes), Set.of(), privileges);
	}

	private static void check(HttpClient client, URI uri, String authorization, int expected, String identity) throws Exception {
		var builder = HttpRequest.newBuilder(uri);
		if (authorization != null)
			builder.header("Authorization", authorization);
		var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(response.body(), expected, response.statusCode());
		if (identity != null)
			assertEquals(identity, response.body());
		else {
			assertTrue(response.body().contains("requestId"));
			assertFalse(response.body().contains("Exception"));
			var error = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("error");
			assertEquals(Set.of("code", "message", "requestId"), error.keySet());
			assertEquals(expected == 401 ? "Authentication required" : "Ingestion permission and server identity required",
					error.get("message").getAsString());
			// Malformed punctuation such as ':' is JSON syntax, not a secret to search for in the whole body.
			if (authorization != null && authorization.length() > 23)
				assertFalse(response.body().contains(authorization.substring(authorization.indexOf(' ') + 1)));
			if (expected == 401)
				assertTrue(response.headers().firstValue("WWW-Authenticate").orElseThrow().startsWith("Bearer"));
		}
	}
}
