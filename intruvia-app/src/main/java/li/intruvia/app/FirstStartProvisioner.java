// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import li.intruvia.rest.session.ViewerConfiguration;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
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

	static void ensureInitialized(RuntimeLock lock, Path runtime, Path templates, Path password, String origin,
			Map<String, String> environment, Path applicationJar) {
		try {
			runtime = runtime.toAbsolutePath().normalize();
			password = password.toAbsolutePath().normalize();
			if (!lock.holds(runtime)) throw ProvisioningFiles.failure();
			Path initialized = runtime.resolve(INITIALIZED);
			Path initializing = runtime.resolve(INITIALIZING);
			boolean done = Files.exists(initialized, LinkOption.NOFOLLOW_LINKS);
			if (Files.exists(initializing, LinkOption.NOFOLLOW_LINKS)) throw ProvisioningFiles.failure();
			if (done) {
				validateExisting(lock, runtime);
				DeploymentDatabase.requireInitialized(environment);
				return;
			}
			try (var entries = Files.list(runtime)) {
				if (entries.anyMatch(path -> !path.getFileName().toString().equals(RuntimeLock.FILE)))
					throw ProvisioningFiles.failure();
			}
			validateOrigin(origin);
			validatePassword(password);
			DeploymentDatabase.requireEmpty(environment);
			ProvisioningFiles.writeNew(initializing, "initializing\n".getBytes(StandardCharsets.UTF_8));
			ProvisioningFiles.forceDirectory(runtime);
			Path stage = runtime.resolve(".intruvia-staging");
			ProvisioningFiles.directory(stage);
			stageRuntime(stage, templates, password, origin, applicationJar);
			for (String name : List.of("config", "data", "temp"))
				ProvisioningFiles.moveNew(stage.resolve(name), runtime.resolve(name));
			Files.delete(stage);
			ProvisioningFiles.forceDirectory(runtime);
			validateRuntime(runtime);
			Path bootstrap = runtime.resolve(".intruvia-bootstrap");
			stageBootstrap(runtime, bootstrap);
			SchemaBootstrapRunner.run(bootstrap, applicationJar, environment);
			DeploymentDatabase.requireInitialized(environment);
			deleteTree(bootstrap);
			ProvisioningFiles.forceDirectory(runtime);
			Path marker = runtime.resolve(".intruvia-initialized.new");
			ProvisioningFiles.writeNew(marker, MARKER);
			ProvisioningFiles.moveNew(marker, initialized);
			Files.delete(initializing);
			ProvisioningFiles.forceDirectory(runtime);
			removePassword(password);
		} catch (ConfigurationFailure e) {
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw ProvisioningFiles.failure();
		} catch (Exception e) {
			throw ProvisioningFiles.failure();
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
		try {
			if (origin == null || !origin.startsWith("https://")) throw ProvisioningFiles.failure();
			new ViewerConfiguration(origin);
		} catch (RuntimeException e) {
			throw ProvisioningFiles.failure();
		}
	}

	private static void validatePassword(Path password) throws Exception {
		RuntimeLock.requireDirectory(password.toAbsolutePath().normalize().getParent());
		RuntimeLock.requireFile(password);
		if (Files.size(password) == 0 || Files.size(password) > 4096) throw ProvisioningFiles.failure();
		byte[] bytes = Files.readAllBytes(password);
		CharBuffer chars = null;
		try {
			chars = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
			int length = chars.limit();
			while (length > 0 && Character.isWhitespace(chars.get(length - 1))) length--;
			if (length == 0 || length > 1024) throw ProvisioningFiles.failure();
		} finally {
			Arrays.fill(bytes, (byte) 0);
			if (chars != null && chars.hasArray()) Arrays.fill(chars.array(), '\0');
		}
	}

	private static void removePassword(Path password) throws Exception {
		if (!Files.exists(password, LinkOption.NOFOLLOW_LINKS)) return;
		RuntimeLock.requireDirectory(password.toAbsolutePath().normalize().getParent());
		RuntimeLock.requireFile(password);
		Files.delete(password);
		ProvisioningFiles.forceDirectory(password.toAbsolutePath().normalize().getParent());
	}

	private static void stageRuntime(Path stage, Path templates, Path password, String origin, Path applicationJar) throws Exception {
		Path config = stage.resolve("config");
		ProvisioningFiles.directory(config);
		ProvisioningFiles.directory(stage.resolve("data"));
		ProvisioningFiles.directory(stage.resolve("temp"));
		byte[] strolch = ProvisioningFiles.template(templates, "StrolchConfiguration.xml");
		validateSchemaFlags(ProvisioningFiles.xml(strolch), false);
		ProvisioningFiles.writeNew(config.resolve("StrolchConfiguration.xml"), strolch);
		ProvisioningFiles.writeNew(config.resolve("PrivilegeConfig.xml"), privilegeConfig(templates));
		byte[] roles = ProvisioningFiles.template(templates, "PrivilegeRoles.xml.example");
		validateViewerRole(roles);
		ProvisioningFiles.writeNew(config.resolve("PrivilegeRoles.xml"), roles);
		Path users = config.resolve("PrivilegeUsers.xml");
		ProvisioningFiles.writeNew(users, ProvisioningFiles.template(templates, "PrivilegeUsers.xml.example"));
		String hash = PrivilegeToolRunner.hash(applicationJar, config, password, stage);
		ProvisioningFiles.replaceStaged(users, viewerUser(Files.readAllBytes(users), hash));
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

	private static void validateViewerRole(byte[] bytes) throws Exception {
		var roles = ProvisioningFiles.xml(bytes).getElementsByTagName("Role");
		int viewers = 0;
		for (int i = 0; i < roles.getLength(); i++)
			if (((Element) roles.item(i)).getAttribute("name").equals("Viewer")) viewers++;
		if (viewers != 1) throw ProvisioningFiles.failure();
	}

	private static byte[] viewerUser(byte[] bytes, String hash) throws Exception {
		Document document = ProvisioningFiles.xml(bytes);
		Element root = document.getDocumentElement();
		if (!root.getTagName().equals("Users")) throw ProvisioningFiles.failure();
		var users = document.getElementsByTagName("User");
		for (int i = 0; i < users.getLength(); i++) {
			Element user = (Element) users.item(i);
			if (user.getAttribute("username").equals("viewer") || user.getAttribute("userId").equals("viewer"))
				throw ProvisioningFiles.failure();
		}
		Element user = document.createElement("User");
		user.setAttribute("userId", "viewer");
		user.setAttribute("username", "viewer");
		user.setAttribute("password", hash);
		append(document, user, "Firstname", "Intruvia");
		append(document, user, "Lastname", "Viewer");
		append(document, user, "Locale", "en-GB");
		append(document, user, "State", "ENABLED");
		Element roles = document.createElement("Roles");
		append(document, roles, "Role", "Viewer");
		user.appendChild(roles);
		root.appendChild(user);
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