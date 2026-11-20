// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import li.intruvia.rest.session.ViewerConfiguration;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FirstStartProvisioner {
	static final String INITIALIZING = ".intruvia-initializing";
	static final String INITIALIZED = ".intruvia-initialized";
	private static final byte[] MARKER = "intruvia-initialized-v1\n".getBytes(StandardCharsets.UTF_8);
	private static final List<String> CONFIG_FILES = List.of("StrolchConfiguration.xml", "PrivilegeConfig.xml",
			"PrivilegeRoles.xml", "PrivilegeUsers.xml", "viewer.properties", "ingestion.properties", "machine-identities.conf");
	private static final SecureRandom RANDOM = new SecureRandom();

	private FirstStartProvisioner() {}

	static char[] ensureInitialized(RuntimeLock lock, Path runtime, Path templates, String origin,
			Map<String, String> environment, Path applicationJar) {
		char[] password = null;
		boolean completed = false;
		String stage = "initialization state validation";
		try {
			runtime = runtime.toAbsolutePath().normalize();
			if (!lock.holds(runtime)) throw ProvisioningFiles.failure();
			Path initialized = runtime.resolve(INITIALIZED);
			Path initializing = runtime.resolve(INITIALIZING);
			boolean done = Files.exists(initialized, LinkOption.NOFOLLOW_LINKS);
			if (Files.exists(initializing, LinkOption.NOFOLLOW_LINKS)) throw ProvisioningFiles.failure();
			if (done) {
				stage = "existing installation validation";
				validateExisting(lock, runtime);
				DeploymentDatabase.requireInitialized(environment);
				return null;
			}
			try (var entries = Files.list(runtime)) {
			if (entries.anyMatch(path -> !path.getFileName().toString().equals(RuntimeLock.FILE)))
					throw ProvisioningFiles.failure();
			}
			stage = "public origin validation";
			validateOrigin(origin);
			stage = "database empty-state verification";
			DeploymentDatabase.requireEmpty(environment);
			stage = "initialization marker creation";
			ProvisioningFiles.writeNew(initializing, "initializing\n".getBytes(StandardCharsets.UTF_8));
			ProvisioningFiles.forceDirectory(runtime);
			stage = "runtime template staging";
			Path staging = runtime.resolve(".intruvia-staging");
			ProvisioningFiles.directory(staging);
			password = generateInitialPassword();
			stage = "runtime template provisioning";
			stageRuntime(staging, templates, password, origin, applicationJar);
			for (String name : List.of("config", "data", "temp"))
				ProvisioningFiles.moveNew(staging.resolve(name), runtime.resolve(name));
			Files.delete(staging);
			ProvisioningFiles.forceDirectory(runtime);
			stage = "staged runtime validation";
			validateRuntime(runtime);
			stage = "schema bootstrap staging";
			Path bootstrap = runtime.resolve(".intruvia-bootstrap");
			stageBootstrap(runtime, bootstrap);
			stage = "PostgreSQL schema bootstrap";
			SchemaBootstrapRunner.run(bootstrap, applicationJar, environment);
			DeploymentDatabase.requireInitialized(environment);
			deleteTree(bootstrap);
			ProvisioningFiles.forceDirectory(runtime);
			stage = "initialization marker commit";
			Path marker = runtime.resolve(".intruvia-initialized.new");
			ProvisioningFiles.writeNew(marker, MARKER);
			ProvisioningFiles.moveNew(marker, initialized);
			Files.delete(initializing);
			ProvisioningFiles.forceDirectory(runtime);
			completed = true;
			return password;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new StartupFailure(stage, e);
		} catch (Exception e) {
			throw new StartupFailure(stage, e);
		} finally {
			if (!completed && password != null) Arrays.fill(password, '\0');
		}
	}

	static char[] resetInitialAccountPassword(RuntimeLock lock, Path runtime, Path applicationJar) throws Exception {
		char[] password = generateInitialPassword();
		boolean updated = false;
		try {
			runtime = runtime.toAbsolutePath().normalize();
			validateExisting(lock, runtime);
			Path config = runtime.resolve("config");
			Path users = config.resolve("PrivilegeUsers.xml");
			RuntimeLock.requireFile(users);
			String hash = PrivilegeToolRunner.hash(applicationJar, config, password, runtime);
			byte[] current = Files.readAllBytes(users);
			byte[] replacement;
			try {
				replacement = replaceInitialPassword(current, hash);
			} finally {
				Arrays.fill(current, (byte) 0);
			}
			try {
				ProvisioningFiles.replaceStaged(users, replacement);
			} finally {
				Arrays.fill(replacement, (byte) 0);
			}
			updated = true;
			return password;
		} finally {
			if (!updated) Arrays.fill(password, '\0');
		}
	}

	static void validateExisting(RuntimeLock lock, Path runtime) throws Exception {
		if (!lock.holds(runtime) || Files.exists(runtime.resolve(INITIALIZING), LinkOption.NOFOLLOW_LINKS))
			throw ProvisioningFiles.failure();
		Path marker = runtime.resolve(INITIALIZED);
		RuntimeLock.requireFile(marker);
		if (Files.size(marker) != MARKER.length || !Arrays.equals(Files.readAllBytes(marker), MARKER))
			throw ProvisioningFiles.failure();
		validateRuntime(runtime);
	}

	static void validateOrigin(String origin) {
		if (origin == null || origin.isBlank())
			throw new ConfigurationFailure("INTRUVIA_PUBLIC_ORIGIN is required");
		try {
			new ViewerConfiguration(origin);
		} catch (IllegalArgumentException e) {
			throw new ConfigurationFailure("INTRUVIA_PUBLIC_ORIGIN must be an HTTP or HTTPS origin with a host and no credentials, path, query, or fragment");
		}
	}

	private static void stageRuntime(Path stage, Path templates, char[] password, String origin, Path applicationJar) throws Exception {
		Path config = stage.resolve("config");
		ProvisioningFiles.directory(config);
		ProvisioningFiles.directory(stage.resolve("data"));
		ProvisioningFiles.directory(stage.resolve("temp"));
		byte[] strolch = ProvisioningFiles.template(templates, "StrolchConfiguration.xml");
		validateSchemaFlags(ProvisioningFiles.xml(strolch), false);
		ProvisioningFiles.writeNew(config.resolve("StrolchConfiguration.xml"), strolch);
		ProvisioningFiles.writeNew(config.resolve("PrivilegeConfig.xml"), privilegeConfig(templates));
		byte[] roles = ProvisioningFiles.template(templates, "PrivilegeRoles.xml.example");
		validateAdministrationRoles(roles);
		ProvisioningFiles.writeNew(config.resolve("PrivilegeRoles.xml"), roles);
		Path users = config.resolve("PrivilegeUsers.xml");
		ProvisioningFiles.writeNew(users, ProvisioningFiles.template(templates, "PrivilegeUsers.xml.example"));
		String hash = PrivilegeToolRunner.hash(applicationJar, config, password, stage);
		ProvisioningFiles.replaceStaged(users, initialAdministrator(Files.readAllBytes(users), hash));
		ProvisioningFiles.writeNew(config.resolve("viewer.properties"), ("origin=" + origin + "\n").getBytes(StandardCharsets.UTF_8));
		ProvisioningFiles.writeNew(config.resolve("ingestion.properties"), ProvisioningFiles.template(templates, "ingestion.properties.example"));
		ProvisioningFiles.writeNew(config.resolve("machine-identities.conf"), new byte[0]);
		ProvisioningFiles.forceDirectory(config);
		ProvisioningFiles.forceDirectory(stage);
	}

	private static byte[] privilegeConfig(Path templates) throws Exception {
		Document document = ProvisioningFiles.xml(ProvisioningFiles.template(templates, "PrivilegeConfig.xml.example"));
		String key = secret();
		String salt;
		do salt = secret(); while (key.equals(salt));
		Set<String> required = new java.util.HashSet<>(Set.of("secretKey", "secretSalt"));
		var parameters = document.getElementsByTagName("Parameter");
		for (int i = 0; i < parameters.getLength(); i++) {
			Element parameter = (Element) parameters.item(i);
			String name = parameter.getAttribute("name");
			if (name.equals("secretKey") || name.equals("secretSalt")) {
				if (!required.remove(name) || !parameter.getAttribute("value").equals("CHANGE-ME")) throw ProvisioningFiles.failure();
				parameter.setAttribute("value", name.equals("secretKey") ? key : salt);
			}
		}
		if (!required.isEmpty()) throw ProvisioningFiles.failure();
		return ProvisioningFiles.xml(document);
	}

	private static String secret() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		try {
			return HexFormat.of().formatHex(bytes);
		} finally {
			Arrays.fill(bytes, (byte) 0);
		}
	}

	static char[] generateInitialPassword() {
		byte[] bytes = new byte[32];
		char[] password = new char[bytes.length * 2];
		char[] hex = "0123456789abcdef".toCharArray();
		RANDOM.nextBytes(bytes);
		try {
			for (int i = 0; i < bytes.length; i++) {
				int value = bytes[i] & 0xff;
				password[i * 2] = hex[value >>> 4];
				password[i * 2 + 1] = hex[value & 0x0f];
			}
			return password;
		} finally {
			Arrays.fill(bytes, (byte) 0);
			Arrays.fill(hex, '\0');
		}
	}

	private static void validateAdministrationRoles(byte[] bytes) throws Exception {
		var roles = ProvisioningFiles.xml(bytes).getElementsByTagName("Role");
		Set<String> required = new java.util.HashSet<>(Set.of("Viewer", "Admin", "TokenOperator", "Producer"));
		for (int i = 0; i < roles.getLength(); i++)
			required.remove(((Element) roles.item(i)).getAttribute("name"));
		if (!required.isEmpty()) throw ProvisioningFiles.failure();
	}

	private static byte[] initialAdministrator(byte[] bytes, String hash) throws Exception {
		Document document = ProvisioningFiles.xml(bytes);
		Element root = document.getDocumentElement();
		if (!root.getTagName().equals("Users")) throw ProvisioningFiles.failure();
		var users = document.getElementsByTagName("User");
		for (int i = 0; i < users.getLength(); i++) {
			Element user = (Element) users.item(i);
			if (user.getAttribute("username").equals("admin") || user.getAttribute("userId").equals("admin"))
				throw ProvisioningFiles.failure();
		}
		Element user = document.createElement("User");
		user.setAttribute("userId", "admin");
		user.setAttribute("username", "admin");
		user.setAttribute("password", hash);
		append(document, user, "Firstname", "Intruvia");
		append(document, user, "Lastname", "Administrator");
		append(document, user, "Locale", "en-GB");
		append(document, user, "State", "ENABLED");
		Element roles = document.createElement("Roles");
		append(document, roles, "Role", "Viewer");
		append(document, roles, "Role", "Admin");
		append(document, roles, "Role", "TokenOperator");
		user.appendChild(roles);
		root.appendChild(user);
		return ProvisioningFiles.xml(document);
	}

	static byte[] replaceViewerPassword(byte[] bytes, String hash) throws Exception {
		return replaceUserPassword(bytes, "viewer", hash);
	}

	private static byte[] replaceInitialPassword(byte[] bytes, String hash) throws Exception {
		String username = hasRole(bytes, "admin", "Admin") ? "admin" : "viewer";
		return replaceUserPassword(bytes, username, hash);
	}

	private static boolean hasRole(byte[] bytes, String username, String roleName) throws Exception {
		Document document = ProvisioningFiles.xml(bytes);
		var users = document.getElementsByTagName("User");
		for (int i = 0; i < users.getLength(); i++) {
			Element user = (Element) users.item(i);
			if (!user.getAttribute("username").equals(username) || !user.getAttribute("userId").equals(username))
				continue;
			var roles = user.getElementsByTagName("Role");
			for (int j = 0; j < roles.getLength(); j++)
				if (roles.item(j).getTextContent().strip().equals(roleName)) return true;
		}
		return false;
	}

	private static byte[] replaceUserPassword(byte[] bytes, String username, String hash) throws Exception {
		if (hash == null || hash.isBlank()) throw ProvisioningFiles.failure();
		Document document = ProvisioningFiles.xml(bytes);
		Element root = document.getDocumentElement();
		if (!root.getTagName().equals("Users")) throw ProvisioningFiles.failure();
		var users = document.getElementsByTagName("User");
		Element account = null;
		for (int i = 0; i < users.getLength(); i++) {
			Element user = (Element) users.item(i);
			boolean matchingId = user.getAttribute("userId").equals(username);
			boolean matchingName = user.getAttribute("username").equals(username);
			if (!matchingId && !matchingName) continue;
			if (!matchingId || !matchingName || account != null) throw ProvisioningFiles.failure();
			account = user;
		}
		if (account == null || !account.hasAttribute("password") || account.getAttribute("password").isBlank())
			throw ProvisioningFiles.failure();
		account.setAttribute("password", hash);
		return ProvisioningFiles.xml(document);
	}

	private static void append(Document document, Element parent, String name, String value) {
		Element element = document.createElement(name);
		element.setTextContent(value);
		parent.appendChild(element);
	}

	private static void stageBootstrap(Path runtime, Path bootstrap) throws Exception {
		ProvisioningFiles.directory(bootstrap);
		Path config = bootstrap.resolve("config");
		ProvisioningFiles.directory(config);
		ProvisioningFiles.directory(bootstrap.resolve("data"));
		ProvisioningFiles.directory(bootstrap.resolve("temp"));
		for (String name : CONFIG_FILES) {
			byte[] bytes = Files.readAllBytes(runtime.resolve("config").resolve(name));
			if (name.equals("StrolchConfiguration.xml")) {
				Document document = ProvisioningFiles.xml(bytes);
				validateSchemaFlags(document, false);
				document.getElementsByTagName("allowSchemaCreation").item(0).setTextContent("true");
				validateSchemaFlags(document, true);
				bytes = ProvisioningFiles.xml(document);
			}
			ProvisioningFiles.writeNew(config.resolve(name), bytes);
		}
		ProvisioningFiles.forceDirectory(config);
		ProvisioningFiles.forceDirectory(bootstrap);
	}

	private static void validateRuntime(Path runtime) throws Exception {
		Path config = runtime.resolve("config");
		RuntimeLock.requireDirectory(config);
		for (String name : CONFIG_FILES) {
			Path file = config.resolve(name);
			if (name.equals("machine-identities.conf") && !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue;
			RuntimeLock.requireFile(file);
		}
		RuntimeLock.requireDirectory(runtime.resolve("data"));
		RuntimeLock.requireDirectory(runtime.resolve("temp"));
		RuntimeConfiguration.validate(runtime);
		validateOrigin(ViewerConfiguration.load(config.resolve("viewer.properties")).origin());
		validateSchemaFlags(ProvisioningFiles.xml(Files.readAllBytes(config.resolve("StrolchConfiguration.xml"))), false);
	}

	private static void validateSchemaFlags(Document document, boolean creation) {
		for (String name : List.of("allowSchemaCreation", "allowSchemaMigration", "allowSchemaDrop",
				"allowDataInitOnSchemaCreate", "runMigrationsOnStart", "pollMigrations")) {
			var nodes = document.getElementsByTagName(name);
			String expected = name.equals("allowSchemaCreation") && creation ? "true" : "false";
			if (nodes.getLength() != 1 || !nodes.item(0).getTextContent().strip().equals(expected))
				throw ProvisioningFiles.failure();
		}
	}

	private static void deleteTree(Path path) throws Exception {
		try (var entries = Files.walk(path)) {
			for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
		}
	}
}