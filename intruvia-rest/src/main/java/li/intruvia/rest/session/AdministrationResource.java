// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.session;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import li.intruvia.core.auth.MachineIdentities;
import li.intruvia.rest.auth.MachineAuthenticationFilter;
import li.intruvia.rest.ingest.IngestHttp;
import li.strolch.privilege.handler.PrivilegeHandler;
import li.strolch.privilege.model.Certificate;
import li.strolch.privilege.model.PersonalAccessTokenRep;
import li.strolch.privilege.model.UserRep;
import li.strolch.privilege.model.UserState;

import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Path("admin")
@ViewerRead
public final class AdministrationResource {
	private static final String ADMIN = "intruvia:admin";
	private static final String VIEWER = "Viewer";
	private static final String ADMIN_ROLE = "Admin";
	private static final String TOKEN_OPERATOR = "TokenOperator";
	private static final String PRODUCER = "Producer";
	private static final String INGEST = MachineAuthenticationFilter.INGEST;
	private static final SecureRandom RANDOM = new SecureRandom();
	private final ViewerSessions sessions;
	private final Supplier<PrivilegeHandler> handlers;
	private final MachineIdentities identities;

	public AdministrationResource(ViewerSessions sessions, Supplier<PrivilegeHandler> handlers, MachineIdentities identities) {
		this.sessions = sessions;
		this.handlers = handlers;
		this.identities = identities;
	}

	@GET
	@Path("users")
	public Response users(@Context SecurityContext security) {
		Certificate certificate = administrator(security);
		try {
			List<Map<String, Object>> users = this.handlers.get().getUsers(certificate).stream()
					.filter(user -> user.getUserState() != UserState.SYSTEM && user.getUserState() != UserState.REMOTE)
					.sorted(Comparator.comparing(UserRep::getUsername))
					.map(AdministrationResource::userView).toList();
			return IngestHttp.json(200, Map.of("users", users));
		} catch (RuntimeException e) {
			throw ViewerHttp.failure(503);
		}
	}

	@POST
	@Path("users")
	public Response createUser(@Context HttpHeaders headers, @Context SecurityContext security, java.io.InputStream body) {
		Certificate certificate = administrator(headers, security);
		Map<String, String> fields = AdminRequest.read(headers, body,
				Set.of("username", "firstName", "lastName", "password", "kind"));
		AdminRequest.requireFields(fields, Set.of("username", "firstName", "lastName", "password", "kind"));
		String username = username(fields.get("username"));
		String firstName = AdminRequest.text(fields, "firstName", 128);
		String lastName = AdminRequest.text(fields, "lastName", 128);
		String kind = fields.get("kind");
		String rawPassword = fields.get("password");
		if (rawPassword == null || rawPassword.isBlank() || rawPassword.length() > 1024 || kind == null ||
				(!kind.equals("viewer") && !kind.equals("administrator")))
			throw ViewerHttp.failure(400);
		Set<String> roles = kind.equals("administrator") ? Set.of(VIEWER, ADMIN_ROLE, TOKEN_OPERATOR) : Set.of(VIEWER);
		char[] password = rawPassword.toCharArray();
		try {
			UserRep user = new UserRep("", username, firstName, lastName, UserState.ENABLED, Set.of(), roles,
					Locale.UK, Map.of(), null);
			UserRep created = this.handlers.get().addUser(certificate, user, password);
			return IngestHttp.json(201, userView(created));
		} catch (RuntimeException e) {
			throw ViewerHttp.failure(409);
		} finally {
			Arrays.fill(password, '\0');
		}
	}

	@PUT
	@Path("users/{username}/password")
	public Response setPassword(@PathParam("username") String username, @Context HttpHeaders headers,
			@Context SecurityContext security, java.io.InputStream body) {
		Certificate certificate = administrator(headers, security);
		username = username(username);
		Map<String, String> fields = AdminRequest.read(headers, body, Set.of("password"));
		AdminRequest.requireFields(fields, Set.of("password"));
		String rawPassword = fields.get("password");
		if (rawPassword == null || rawPassword.isBlank() || rawPassword.length() > 1024)
			throw ViewerHttp.failure(400);
		char[] password = rawPassword.toCharArray();
		try {
			UserRep user = this.handlers.get().getUser(certificate, username);
			if (user == null || user.getUserState() == UserState.SYSTEM || user.getUserState() == UserState.REMOTE ||
					user.getRoles().contains(PRODUCER))
				throw ViewerHttp.failure(404);
			return IngestHttp.json(200, userView(this.handlers.get().setUserPassword(certificate, username, password)));
		} catch (ViewerException e) {
			throw e;
		} catch (RuntimeException e) {
			throw ViewerHttp.failure(400);
		} finally {
			Arrays.fill(password, '\0');
		}
	}

	@PUT
	@Path("users/{username}/state")
	public Response setUserState(@PathParam("username") String username, @Context HttpHeaders headers,
			@Context SecurityContext security, java.io.InputStream body) {
		Certificate certificate = administrator(headers, security);
		username = username(username);
		Map<String, String> fields = AdminRequest.read(headers, body, Set.of("state"));
		AdminRequest.requireFields(fields, Set.of("state"));
		String stateValue = fields.get("state");
		if (!"ENABLED".equals(stateValue) && !"DISABLED".equals(stateValue))
			throw ViewerHttp.failure(400);
		UserState state = UserState.valueOf(stateValue);
		try {
			PrivilegeHandler handler = this.handlers.get();
			UserRep user = handler.getUser(certificate, username);
			if (user == null || user.getUserState() == UserState.SYSTEM || user.getUserState() == UserState.REMOTE ||
					user.getRoles().contains(PRODUCER) ||
					(certificate.getUsername().equals(username) && state == UserState.DISABLED))
				throw ViewerHttp.failure(409);
			if (user.getRoles().contains(ADMIN_ROLE) && user.getUserState() == UserState.ENABLED && state == UserState.DISABLED &&
					activeAdministrators(handler.getUsers(certificate)) <= 1)
				throw ViewerHttp.failure(409);
			return IngestHttp.json(200, userView(handler.setUserState(certificate, username, state)));
		} catch (ViewerException e) {
			throw e;
		} catch (RuntimeException e) {
			throw ViewerHttp.failure(409);
		}
	}

	@DELETE
	@Path("users/{username}")
	public Response deleteUser(@PathParam("username") String username, @Context HttpHeaders headers,
			@Context SecurityContext security) {
		Certificate certificate = administrator(headers, security);
		username = username(username);
		try {
			PrivilegeHandler handler = this.handlers.get();
			UserRep user = handler.getUser(certificate, username);
			if (user == null) throw ViewerHttp.failure(404);
			if (user.getUserState() == UserState.SYSTEM || user.getUserState() == UserState.REMOTE ||
					user.getRoles().contains(PRODUCER) || user.getRoles().contains(ADMIN_ROLE) ||
					certificate.getUsername().equals(username))
				throw ViewerHttp.failure(409);
			handler.removeUser(certificate, username);
			return Response.noContent().header("Cache-Control", "no-store").build();
		} catch (ViewerException e) {
			throw e;
		} catch (RuntimeException e) {
			throw ViewerHttp.failure(409);
		}
	}

	@GET
	@Path("machines")
	public Response machines(@Context SecurityContext security) {
		Certificate certificate = administrator(security);
		try {
			PrivilegeHandler handler = this.handlers.get();
			Map<String, UserRep> producers = new HashMap<>();
			for (UserRep user : handler.getUsers(certificate))
				if (user.getRoles().contains(PRODUCER)) producers.put(user.getUsername(), user);
			Set<String> usernames = new java.util.TreeSet<>(this.identities.instances().keySet());
			usernames.addAll(producers.keySet());
			List<Map<String, Object>> machines = new ArrayList<>();
			for (String username : usernames) {
				UserRep user = producers.get(username);
				List<Map<String, Object>> tokens = user == null ? List.of() : handler.getPersonalAccessTokens(certificate, username)
						.stream().sorted(Comparator.comparing(PersonalAccessTokenRep::name))
						.map(AdministrationResource::tokenView).toList();
				Map<String, Object> machine = new HashMap<>();
				machine.put("username", username);
				machine.put("instanceId", this.identities.instances().getOrDefault(username, ""));
				machine.put("state", user == null ? "MISSING" : user.getUserState().name());
				machine.put("tokens", tokens);
				machines.add(Map.copyOf(machine));
			}
			return IngestHttp.json(200, Map.of("machines", machines));
		} catch (RuntimeException e) {
			throw ViewerHttp.failure(503);
		}
	}

	@POST
	@Path("machines")
	public Response createMachine(@Context HttpHeaders headers, @Context SecurityContext security, java.io.InputStream body) {
		Certificate certificate = administrator(headers, security);
		Map<String, String> fields = AdminRequest.read(headers, body,
				Set.of("username", "instanceId", "tokenName", "validDays"));
		AdminRequest.requireFields(fields, Set.of("username", "instanceId", "tokenName", "validDays"));
		String username = username(fields.get("username"));
		String instanceId = instanceId(fields.get("instanceId"));
		String tokenName = tokenName(fields.get("tokenName"));
		int validDays = AdminRequest.days(fields);
		if (this.identities.instances().containsKey(username) || this.identities.instances().containsValue(instanceId))
			throw ViewerHttp.failure(409);
		PrivilegeHandler handler = this.handlers.get();
		try {
			if (handler.getUser(certificate, username) != null) throw ViewerHttp.failure(409);
		} catch (ViewerException e) {
			throw e;
		} catch (RuntimeException e) {
			throw ViewerHttp.failure(503);
		}
		char[] machinePassword = randomPassword();
		String tokenId = null;
		boolean userCreated = false;
		try {
			UserRep machine = new UserRep("", username, username, "Machine", UserState.ENABLED, Set.of(), Set.of(PRODUCER),
					Locale.UK, Map.of(), null);
			handler.addUser(certificate, machine, machinePassword);
			userCreated = true;
			IssuedToken issued = issueToken(handler, certificate, username, tokenName, validDays);
			tokenId = issued.id();
			this.identities.setInstance(username, instanceId);
			return IngestHttp.json(201, Map.of("username", username, "instanceId", instanceId,
					"tokenId", issued.id(), "token", issued.value()));
		} catch (ViewerException e) {
			rollbackMachine(handler, certificate, username, tokenId, userCreated);
			throw e;
		} catch (IllegalArgumentException e) {
			rollbackMachine(handler, certificate, username, tokenId, userCreated);
			throw ViewerHttp.failure(409);
		} catch (Exception e) {
			rollbackMachine(handler, certificate, username, tokenId, userCreated);
			throw ViewerHttp.failure(503);
		} finally {
			Arrays.fill(machinePassword, '\0');
		}
	}

	@PUT
	@Path("machines/{username}")
	public Response setMachineIdentity(@PathParam("username") String username, @Context HttpHeaders headers,
			@Context SecurityContext security, java.io.InputStream body) {
		Certificate certificate = administrator(headers, security);
		username = username(username);
		Map<String, String> fields = AdminRequest.read(headers, body, Set.of("instanceId"));
		AdminRequest.requireFields(fields, Set.of("instanceId"));
		String instanceId = instanceId(fields.get("instanceId"));
		try {
			requireProducer(certificate, username);
			this.identities.setInstance(username, instanceId);
			return IngestHttp.json(200, Map.of("username", username, "instanceId", instanceId));
		} catch (ViewerException e) {
			throw e;
		} catch (IllegalArgumentException e) {
			throw ViewerHttp.failure(409);
		} catch (Exception e) {
			throw ViewerHttp.failure(503);
		}
	}

	@DELETE
	@Path("machines/{username}")
	public Response deleteMachine(@PathParam("username") String username, @Context HttpHeaders headers,
			@Context SecurityContext security) {
		Certificate certificate = administrator(headers, security);
		username = username(username);
		try {
			PrivilegeHandler handler = this.handlers.get();
			UserRep user = handler.getUser(certificate, username);
			if (user != null && !user.getRoles().contains(PRODUCER)) throw ViewerHttp.failure(409);
			if (user != null && !handler.getPersonalAccessTokens(certificate, username).isEmpty())
				throw ViewerHttp.failure(409);
			this.identities.remove(username);
			if (user != null) handler.removeUser(certificate, username);
			return Response.noContent().header("Cache-Control", "no-store").build();
		} catch (ViewerException e) {
			throw e;
		} catch (Exception e) {
			throw ViewerHttp.failure(503);
		}
	}

	@POST
	@Path("machines/{username}/tokens")
	public Response createMachineToken(@PathParam("username") String username, @Context HttpHeaders headers,
			@Context SecurityContext security, java.io.InputStream body) {
		Certificate certificate = administrator(headers, security);
		username = username(username);
		Map<String, String> fields = AdminRequest.read(headers, body, Set.of("name", "validDays"));
		AdminRequest.requireFields(fields, Set.of("name", "validDays"));
		String name = tokenName(fields.get("name"));
		int validDays = AdminRequest.days(fields);
		try {
			requireMappedProducer(certificate, username);
			IssuedToken token = issueToken(this.handlers.get(), certificate, username, name, validDays);
			return IngestHttp.json(201, Map.of("username", username, "tokenId", token.id(), "token", token.value()));
		} catch (ViewerException e) {
			throw e;
		} catch (RuntimeException e) {
			throw ViewerHttp.failure(503);
		}
	}

	@DELETE
	@Path("machines/{username}/tokens/{tokenId}")
	public Response revokeMachineToken(@PathParam("username") String username, @PathParam("tokenId") String tokenId,
			@Context HttpHeaders headers, @Context SecurityContext security) {
		Certificate certificate = administrator(headers, security);
		username = username(username);
		if (tokenId == null || !tokenId.matches("[0-9a-fA-F-]{36}")) throw ViewerHttp.failure(404);
		try {
			requireProducer(certificate, username);
			PrivilegeHandler handler = this.handlers.get();
			boolean exists = handler.getPersonalAccessTokens(certificate, username).stream()
					.anyMatch(token -> token.tokenId().equals(tokenId));
			if (!exists) throw ViewerHttp.failure(404);
			handler.removePersonalAccessToken(certificate, tokenId);
			return Response.noContent().header("Cache-Control", "no-store").build();
		} catch (ViewerException e) {
			throw e;
		} catch (RuntimeException e) {
			throw ViewerHttp.failure(503);
		}
	}

	private Certificate administrator(SecurityContext security) {
		if (security == null || !(security.getUserPrincipal() instanceof ViewerAuthenticationFilter.Identity identity))
			throw ViewerHttp.failure(401);
		Certificate certificate = identity.certificate();
		this.sessions.requirePrivilege(certificate, ADMIN);
		return certificate;
	}

	private Certificate administrator(HttpHeaders headers, SecurityContext security) {
		this.sessions.checkRequest(headers, true);
		return administrator(security);
	}

	private void requireProducer(Certificate certificate, String username) {
		UserRep user = this.handlers.get().getUser(certificate, username);
		if (user == null || !user.getRoles().contains(PRODUCER) || user.getUserState() == UserState.SYSTEM)
			throw ViewerHttp.failure(404);
	}

	private void requireMappedProducer(Certificate certificate, String username) {
		requireProducer(certificate, username);
		if (!this.identities.instances().containsKey(username)) throw ViewerHttp.failure(409);
	}

	private static Map<String, Object> userView(UserRep user) {
		return Map.of("username", user.getUsername(), "firstName", user.getFirstname(), "lastName", user.getLastname(),
				"state", user.getUserState().name(), "roles", user.getRoles().stream().sorted().toList());
	}

	private static Map<String, Object> tokenView(PersonalAccessTokenRep token) {
		Map<String, Object> result = new HashMap<>();
		result.put("tokenId", token.tokenId());
		result.put("name", token.name());
		result.put("validFrom", token.validFrom().toInstant().toString());
		result.put("validTo", token.validTo().toInstant().toString());
		result.put("lastUsed", token.lastUsed() == null ? "" : token.lastUsed().toInstant().toString());
		result.put("privileges", token.privileges().stream().sorted().toList());
		return Map.copyOf(result);
	}

	private static int activeAdministrators(List<UserRep> users) {
		return (int) users.stream().filter(user -> user.getUserState() == UserState.ENABLED &&
				user.getRoles().contains(ADMIN_ROLE)).count();
	}

	private static String username(String value) {
		if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,127}")) throw ViewerHttp.failure(400);
		return value;
	}

	private static String instanceId(String value) {
		if (!MachineIdentities.validId(value)) throw ViewerHttp.failure(400);
		return value;
	}

	private static String tokenName(String value) {
		if (value == null || value.isBlank() || value.length() > 64 ||
				value.chars().anyMatch(Character::isISOControl)) throw ViewerHttp.failure(400);
		return value.strip();
	}

	private static IssuedToken issueToken(PrivilegeHandler handler, Certificate certificate, String username,
			String name, int validDays) {
		ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
		String value = handler.createPersonalAccessToken(certificate, username, name, now.minusMinutes(1),
				now.plusDays(validDays), Set.of(PRODUCER), Set.of(INGEST));
		String[] parts = value.split(":", 2);
		if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank())
			throw ViewerHttp.failure(503);
		return new IssuedToken(parts[0], parts[0] + ":" + parts[1]);
	}

	private static char[] randomPassword() {
		byte[] bytes = new byte[32];
		char[] result = new char[64];
		char[] hex = "0123456789abcdef".toCharArray();
		RANDOM.nextBytes(bytes);
		try {
			for (int i = 0; i < bytes.length; i++) {
				int value = bytes[i] & 0xff;
				result[i * 2] = hex[value >>> 4];
				result[i * 2 + 1] = hex[value & 0x0f];
			}
			return result;
		} finally {
			Arrays.fill(bytes, (byte) 0);
			Arrays.fill(hex, '\0');
		}
	}

	private static void rollbackMachine(PrivilegeHandler handler, Certificate certificate, String username, String tokenId,
			boolean userCreated) {
		if (tokenId != null) {
			try { handler.removePersonalAccessToken(certificate, tokenId); } catch (RuntimeException ignored) { }
		}
		if (userCreated) {
			try { handler.removeUser(certificate, username); } catch (RuntimeException ignored) { }
		}
	}

	private record IssuedToken(String id, String value) {}
}
