// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import com.google.gson.JsonParser;
import li.intruvia.rest.session.ViewerException;
import li.intruvia.rest.session.ViewerConfiguration;
import li.intruvia.rest.session.ViewerSessions;
import li.strolch.privilege.handler.DefaultEncryptionHandler;
import li.strolch.runtime.sessions.StrolchSessionHandler;
import org.junit.Test;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ViewerSessionIT {
	private static final String ORIGIN = "https://intruvia.example.org";
	private static final String LOGIN = "{\"username\":\"viewer\",\"password\":\"viewer-test-password\"}";
	private static final String ADMIN_PASSWORD = "admin-test-password";

	@Test(timeout = 60000)
	public void cookiesCsrfAuthorizationAndLogoutUseFrameworkSession() throws Exception {
		try (var database = database(ORIGIN); var app = new IntruviaApplication(database.runtime, 0);
				var client = HttpClient.newHttpClient()) {
			app.start();
			check(send(client, app, "GET", "", Map.of()), 401);
			check(send(client, app, "POST", LOGIN, Map.of("Content-Type", "application/json")), 403);
			for (String origin : List.of("null", "https://evil.example", ORIGIN + ".evil", ORIGIN + "/"))
				check(send(client, app, "POST", LOGIN, Map.of("Origin", origin, "X-Intruvia-CSRF", "1")), 403);
			check(send(client, app, "POST", LOGIN, Map.of("Origin", ORIGIN)), 403);
			check(send(client, app, "POST", LOGIN, headers("Sec-Fetch-Site", "cross-site")), 403);
			check(send(client, app, "POST", LOGIN, headers("Content-Type", "text/plain")), 415);
			check(send(client, app, "POST", LOGIN, headers("Content-Encoding", "gzip")), 415);
			for (String invalid : List.of("{", "{}", LOGIN.replace("viewer\",", "viewer\",\"extra\":0,"),
					LOGIN.replace("\"username\":", "\"username\":\"other\",\"username\":"), LOGIN + "{}"))
				check(send(client, app, "POST", invalid, headers()), 400);
			check(send(client, app, "POST", " ".repeat(4097), headers()), 413);
			check(send(client, app, "POST", LOGIN.replace("viewer-test-password", "wrong"), headers()), 401);
			check(send(client, app, "POST", LOGIN.replace("viewer\"", "denied\""), headers()), 403);
			check(send(client, app, "POST", LOGIN.replace("viewer\"", "disabled\""), headers()), 401);
			check(send(client, app, "POST", LOGIN.replace("viewer\"", "policydenied\""), headers()), 403);
			var login = check(send(client, app, "POST", LOGIN, headers()), 200);
			String setCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
			for (String flag : List.of("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1"))
				assertTrue(setCookie, setCookie.contains(flag));
			assertFalse(setCookie.contains("Domain="));
			String cookie = setCookie.split(";", 2)[0];
			String token = cookie.substring(cookie.indexOf('=') + 1);
			assertEquals(Set.of("username"), JsonParser.parseString(login.body()).getAsJsonObject().keySet());
			assertFalse(login.body().contains(token));
			var sessions = new ViewerSessions(() -> app.agent().getContainer().getComponent(StrolchSessionHandler.class),
					new ViewerConfiguration(ORIGIN), Clock.systemUTC());
			var certificate = sessions.validateToken(token);
			assertEquals("viewer", certificate.getUsername());
			assertEquals(Set.of("event:read"), app.agent().getPrivilegeHandler().validate(certificate).getPrivileges().keySet());
			check(send(client, app, "GET", "", Map.of("Cookie", cookie)), 200);
			check(send(client, app, "GET", "", Map.of("Cookie", cookie, "Origin", "https://evil.example")), 403);
			check(send(client, app, "GET", "", Map.of("Cookie", cookie + "; " + cookie)), 401);
			check(send(client, app, "GET", "", Map.of("Cookie", "IntruviaSession=" + certificate.getSessionId())), 401);
			check(send(client, app, "GET", "", Map.of("Cookie", cookie + "bad")), 401);
			check(send(client, app, "GET", "", Map.of("Authorization", "Bearer " + token)), 401);
			// Real PAT, even with read scope and an owner who can read, must never authenticate a viewer.
			var handler = app.agent().getPrivilegeHandler().getPrivilegeHandler();
			var denied = handler.authenticate("denied", "viewer-test-password".toCharArray(), "intruvia-viewer",
					li.strolch.privilege.model.Usage.ANY, false);
			check(send(client, app, "GET", "", Map.of("Cookie", "IntruviaSession=" + denied.getAuthToken())), 403);
			var duplicateOrigin = HttpRequest.newBuilder(URI.create(base(app) + "/session"))
					.header("Origin", ORIGIN).header("Origin", ORIGIN).header("X-Intruvia-CSRF", "1")
					.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(LOGIN)).build();
			check(client.send(duplicateOrigin, HttpResponse.BodyHandlers.ofString()), 403);
			var duplicateCsrf = HttpRequest.newBuilder(URI.create(base(app) + "/session"))
					.header("Origin", ORIGIN).header("X-Intruvia-CSRF", "1").header("X-Intruvia-CSRF", "1")
					.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(LOGIN)).build();
			check(client.send(duplicateCsrf, HttpResponse.BodyHandlers.ofString()), 403);
			var preflight = HttpRequest.newBuilder(URI.create(base(app) + "/session"))
					.header("Origin", "https://evil.example").header("Access-Control-Request-Method", "POST")
					.header("Access-Control-Request-Headers", "X-Intruvia-CSRF, Content-Type")
					.method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
			assertFalse(client.send(preflight, HttpResponse.BodyHandlers.ofString()).headers()
					.firstValue("Access-Control-Allow-Origin").isPresent());
			var owner = handler.authenticate("issuer", "viewer-test-password".toCharArray(), false);
			String pat = handler.createPersonalAccessToken(owner, "viewer-test", ZonedDateTime.now().minusMinutes(1),
					ZonedDateTime.now().plusMinutes(5), Set.of(), Set.of("event:read"));
			var patCertificate = handler.authenticatePersonalAccessToken(pat, "intruvia-viewer");
			for (String credential : List.of(pat, patCertificate.getAuthToken())) {
				check(send(client, app, "GET", "", Map.of("Authorization", "Bearer " + credential)), 401);
				check(send(client, app, "GET", "", Map.of("Cookie", "IntruviaSession=" + credential)), 401);
				check(send(client, app, "GET", "", Map.of("Cookie", cookie, "Authorization", "Bearer " + credential)), 401);
			}
			var ingest = HttpRequest.newBuilder(URI.create(base(app) + "/fail2ban/events"))
					.header("Cookie", cookie).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
			assertEquals(401, client.send(ingest, HttpResponse.BodyHandlers.ofString()).statusCode());
			check(send(client, app, "DELETE", "", Map.of("Cookie", cookie, "Origin", ORIGIN)), 403);
			check(send(client, app, "GET", "", Map.of("Cookie", cookie)), 200);
			var logout = check(send(client, app, "DELETE", "", headers("Cookie", cookie)), 204);
			assertTrue(logout.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
			check(send(client, app, "GET", "", Map.of("Cookie", cookie)), 401);
			assertThrows(ViewerException.class, () -> sessions.validateToken(token));
			assertThrows(li.strolch.privilege.base.PrivilegeException.class, () -> handler.validate(certificate));
			// Login again yields a fresh token; presenting it during login rotates and invalidates it.
			String second = cookie(check(send(client, app, "POST", LOGIN, headers()), 200));
			String third = cookie(check(send(client, app, "POST", LOGIN, headers("Cookie", second)), 200));
			assertNotEquals(second, third);
			check(send(client, app, "GET", "", Map.of("Cookie", second)), 401);
			check(send(client, app, "GET", "", Map.of("Cookie", third)), 200);
		}
	}

	@Test(timeout = 120000)
	public void administratorManagesViewersMachinesScopedTokensAndLiveMappings() throws Exception {
		try (var database = database(ORIGIN); var client = HttpClient.newHttpClient()) {
			addAdministrator(database);
			String machineToken;
			String replacementToken;
			String machineTokenId;
			try (var app = new IntruviaApplication(database.runtime, 0)) {
				app.start();
				String viewerCookie = cookie(check(send(client, app, "POST", LOGIN, headers()), 200));
				check(adminSend(client, app, "GET", "/admin/users", "", Map.of("Cookie", viewerCookie)), 403);
				check(adminSend(client, app, "GET", "/admin/users", "", Map.of()), 401);
				check(adminSend(client, app, "POST", "/admin/users", "{}", Map.of("Cookie", viewerCookie,
						"Content-Type", "application/json")), 403);

				String adminCookie = cookie(check(send(client, app, "POST",
						"{\"username\":\"admin\",\"password\":\"" + ADMIN_PASSWORD + "\"}", headers()), 200));
				var createViewer = adminSend(client, app, "POST", "/admin/users",
						"{\"username\":\"viewer.new\",\"firstName\":\"New\",\"lastName\":\"Viewer\"," +
						"\"password\":\"new-viewer-test-password\",\"kind\":\"viewer\"}", headers("Cookie", adminCookie));
				check(createViewer, 201);
				assertFalse(createViewer.body().contains("new-viewer-test-password"));
				check(adminSend(client, app, "PUT", "/admin/users/viewer.new/password",
						"{\"password\":\"configured-viewer-test-password\"}", headers("Cookie", adminCookie)), 200);
				check(send(client, app, "POST", "{\"username\":\"viewer.new\",\"password\":" +
						"\"configured-viewer-test-password\"}", headers()), 200);

				var createMachine = check(adminSend(client, app, "POST", "/admin/machines",
						"{\"username\":\"ingest.east\",\"instanceId\":\"edge-east\",\"tokenName\":\"fail2ban-east\",\"validDays\":30}",
						headers("Cookie", adminCookie)), 201);
				var machine = JsonParser.parseString(createMachine.body()).getAsJsonObject();
				machineToken = machine.get("token").getAsString();
				machineTokenId = machine.get("tokenId").getAsString();
				var handler = app.agent().getPrivilegeHandler().getPrivilegeHandler();
				var machineCertificate = handler.authenticatePersonalAccessToken(machineToken, "intruvia-ingest");
				assertEquals("ingest.east", machineCertificate.getUsername());
				assertEquals(Set.of("event:ingest"), handler.validate(machineCertificate).getPrivileges().keySet());
				var machineList = check(adminSend(client, app, "GET", "/admin/machines", "", headers("Cookie", adminCookie)), 200);
				assertFalse(machineList.body().contains(machineToken));
				assertTrue(machineList.body().contains("edge-east"));
				check(adminSend(client, app, "PUT", "/admin/machines/ingest.east", "{\"instanceId\":\"edge-west\"}",
						headers("Cookie", adminCookie)), 200);
				assertEquals("ingest.east=edge-west\n", Files.readString(database.runtime.resolve("config/machine-identities.conf")));
				assertEquals(Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
						java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
						Files.getPosixFilePermissions(database.runtime.resolve("config/machine-identities.conf")));
				check(ingest(client, app, machineToken), 400);

				Map<String, String> noCsrf = Map.of("Cookie", adminCookie, "Content-Type", "application/json");
				check(adminSend(client, app, "POST", "/admin/machines/ingest.east/tokens",
						"{\"name\":\"rotation-east\",\"validDays\":30}", noCsrf), 403);
				var rotate = check(adminSend(client, app, "POST", "/admin/machines/ingest.east/tokens",
						"{\"name\":\"rotation-east\",\"validDays\":30}", headers("Cookie", adminCookie)), 201);
				replacementToken = JsonParser.parseString(rotate.body()).getAsJsonObject().get("token").getAsString();
				var replacementCertificate = handler.authenticatePersonalAccessToken(replacementToken, "intruvia-ingest");
				assertEquals(Set.of("event:ingest"), handler.validate(replacementCertificate).getPrivileges().keySet());
				check(adminSend(client, app, "DELETE", "/admin/machines/ingest.east/tokens/" + machineTokenId, "",
						headers("Cookie", adminCookie)), 204);
				check(ingest(client, app, machineToken), 401);
				check(adminSend(client, app, "DELETE", "/admin/machines/ingest.east", "", headers("Cookie", adminCookie)), 409);
			}

			try (var app = new IntruviaApplication(database.runtime, 0)) {
				app.start();
				String adminCookie = cookie(check(send(client, app, "POST",
						"{\"username\":\"admin\",\"password\":\"" + ADMIN_PASSWORD + "\"}", headers()), 200));
				var machines = check(adminSend(client, app, "GET", "/admin/machines", "", headers("Cookie", adminCookie)), 200);
				assertTrue(machines.body().contains("edge-west"));
				assertFalse(machines.body().contains(replacementToken));
				check(ingest(client, app, replacementToken), 400);
				var machine = JsonParser.parseString(machines.body()).getAsJsonObject().getAsJsonArray("machines")
						.get(0).getAsJsonObject();
				String tokenId = machine.getAsJsonArray("tokens").get(0).getAsJsonObject().get("tokenId").getAsString();
				check(adminSend(client, app, "DELETE", "/admin/machines/ingest.east/tokens/" + tokenId, "",
						headers("Cookie", adminCookie)), 204);
				check(adminSend(client, app, "DELETE", "/admin/machines/ingest.east", "", headers("Cookie", adminCookie)), 204);
				assertEquals("", Files.readString(database.runtime.resolve("config/machine-identities.conf")));
				check(adminSend(client, app, "DELETE", "/admin/users/viewer.new", "", headers("Cookie", adminCookie)), 204);
			}
		}
	}

	@Test(timeout = 60000)
	public void expiryInvalidatesHttpAndSharedSocketValidator() throws Exception {
		String httpOrigin = "http://intruvia.example.org:8080";
		AtomicReference<Instant> time = new AtomicReference<>(Instant.now());
		Clock clock = new Clock() {
			@Override public ZoneId getZone() { return ZoneOffset.UTC; }
			@Override public Clock withZone(ZoneId zone) { return this; }
			@Override public Instant instant() { return time.get(); }
		};
		try (var database = database(httpOrigin); var app = new IntruviaApplication(database.runtime, 0, clock);
				var client = HttpClient.newHttpClient()) {
			app.start();
			var headers = headers();
			headers.put("Origin", httpOrigin);
			var login = check(send(client, app, "POST", LOGIN, headers), 200);
			assertFalse(login.headers().firstValue("Set-Cookie").orElseThrow().contains("Secure"));
			String cookie = cookie(login);
			String token = cookie.substring(cookie.indexOf('=') + 1);
			var sessions = new ViewerSessions(() -> app.agent().getContainer().getComponent(StrolchSessionHandler.class),
					new ViewerConfiguration(httpOrigin), clock);
			var certificate = sessions.validateToken(token);
			time.set(certificate.getLoginTime().toInstant().plusSeconds(1799));
			check(send(client, app, "GET", "", Map.of("Cookie", cookie)), 200);
			time.set(certificate.getLoginTime().toInstant().plusSeconds(1800));
			check(send(client, app, "GET", "", Map.of("Cookie", cookie)), 401);
			assertThrows(ViewerException.class, () -> sessions.validateToken(token));
			assertThrows(li.strolch.privilege.base.PrivilegeException.class,
					() -> app.agent().getPrivilegeHandler().getPrivilegeHandler().validate(certificate));
		}
	}

	@Test(timeout = 60000)
	public void missingConfigurationDisablesViewerSessions() throws Exception {
		try (var database = new DatabaseFixture(); var app = new IntruviaApplication(database.runtime, 0);
				var client = HttpClient.newHttpClient()) {
			app.start();
			check(send(client, app, "POST", LOGIN, headers()), 503);
			check(send(client, app, "GET", "", Map.of()), 503);
		}
	}

	static DatabaseFixture database(String origin) throws Exception {
		var database = new DatabaseFixture();
		Files.writeString(database.runtime.resolve("config/viewer.properties"), "origin=" + origin + "\n");
		var encryption = new DefaultEncryptionHandler();
		encryption.initialize(Map.of("hashAlgorithm", "PBKDF2WithHmacSHA512", "hashIterations", "10000", "hashKeyLength", "256"));
		String password = encryption.hashPassword("viewer-test-password".toCharArray(), encryption.nextSalt()).toString();
		var users = database.runtime.resolve("config/PrivilegeUsers.xml");
		String xml = Files.readString(users);
		for (String name : List.of("viewer", "denied", "issuer", "disabled", "policydenied"))
			xml = xml.replace("</Users>", """
					<User userId="%s" username="%s" password="%s"><Firstname>Test</Firstname><Lastname>Viewer</Lastname>
					<Locale>en-GB</Locale><State>%s</State><Roles><Role>%s</Role></Roles></User></Users>
					""".formatted(name, name, password, name.equals("disabled") ? "DISABLED" : "ENABLED", name));
		Files.writeString(users, xml);
		var roles = database.runtime.resolve("config/PrivilegeRoles.xml");
		Files.writeString(roles, Files.readString(roles).replace("</Roles>", """
				<Role name="viewer"><Privilege name="event:read" policy="DefaultPrivilege"><AllAllowed>true</AllAllowed></Privilege></Role>
				<Role name="disabled"><Privilege name="event:read" policy="DefaultPrivilege"><AllAllowed>true</AllAllowed></Privilege></Role>
				<Role name="denied"><Privilege name="event:ingest" policy="DefaultPrivilege"><AllAllowed>true</AllAllowed></Privilege></Role>
				<Role name="policydenied"><Privilege name="event:read" policy="DefaultPrivilege">
				<Deny>event:read</Deny></Privilege></Role>
				<Role name="issuer">
				<Privilege name="event:read" policy="DefaultPrivilege"><AllAllowed>true</AllAllowed></Privilege>
				<Privilege name="PrivilegePersonalAccessToken" policy="DefaultPrivilege"><AllAllowed>true</AllAllowed></Privilege>
				</Role></Roles>
				"""));
		return database;
	}

	static void addAdministrator(DatabaseFixture database) throws Exception {
		var encryption = new DefaultEncryptionHandler();
		encryption.initialize(Map.of("hashAlgorithm", "PBKDF2WithHmacSHA512", "hashIterations", "10000", "hashKeyLength", "256"));
		String hash = encryption.hashPassword(ADMIN_PASSWORD.toCharArray(), encryption.nextSalt()).toString();
		Path users = database.runtime.resolve("config/PrivilegeUsers.xml");
		Files.writeString(users, Files.readString(users).replace("</Users>", """
				<User userId="admin" username="admin" password="%s"><Firstname>Test</Firstname><Lastname>Administrator</Lastname>
				<Locale>en-GB</Locale><State>ENABLED</State><Roles><Role>Viewer</Role><Role>Admin</Role><Role>TokenOperator</Role></Roles></User></Users>
				""".formatted(hash)));
	}

	private static String base(IntruviaApplication app) { return "http://127.0.0.1:" + app.port() + "/api/v1"; }
	private static String cookie(HttpResponse<String> response) {
		return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
	}

	private static Map<String, String> headers(String... extra) {
		var headers = new HashMap<>(Map.of("Origin", ORIGIN, "X-Intruvia-CSRF", "1", "Content-Type", "application/json"));
		for (int i = 0; i < extra.length; i += 2) headers.put(extra[i], extra[i + 1]);
		return headers;
	}

	private static HttpResponse<String> send(HttpClient client, IntruviaApplication app, String method, String body,
			Map<String, String> headers) throws Exception {
		var request = HttpRequest.newBuilder(URI.create(base(app) + "/session")).timeout(Duration.ofSeconds(10));
		headers.forEach(request::header);
		return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private static HttpResponse<String> adminSend(HttpClient client, IntruviaApplication app, String method, String path,
			String body, Map<String, String> headers) throws Exception {
		var request = HttpRequest.newBuilder(URI.create(base(app) + path)).timeout(Duration.ofSeconds(10));
		headers.forEach(request::header);
		var publisher = (method.equals("GET") || method.equals("DELETE")) && body.isEmpty() ?
				HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
		return client.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
	}

	private static HttpResponse<String> ingest(HttpClient client, IntruviaApplication app, String token) throws Exception {
		var request = HttpRequest.newBuilder(URI.create(base(app) + "/fail2ban/events"))
				.header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{}")).build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private static HttpResponse<String> check(HttpResponse<String> response, int status) {
		assertEquals(response.body(), status, response.statusCode());
		assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
		assertFalse(response.headers().firstValue("Access-Control-Allow-Origin").isPresent());
		assertFalse(response.body().contains("viewer-test-password"));
		if (status >= 400) {
			var error = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("error");
			assertEquals(Set.of("code", "message", "requestId"), error.keySet());
			UUID.fromString(error.get("requestId").getAsString());
			assertFalse(response.body().contains("Exception"));
		}
		return response;
	}
}
