// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Element;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class FirstStartProvisionerIT {
	private static final String PASSWORD = "viewer-provisioning-canary";
	private static final String ORIGIN = "https://intruvia.example.org";
	private static final Pattern STARTED = Pattern.compile("Intruvia started on 127\\.0\\.0\\.1:(\\d+)");
	@Rule public TemporaryFolder temporary = new TemporaryFolder();
	private int logNumber;

	@Test(timeout = 240000)
	public void freshInitializationLoginOfflineLockAndRestartPreserveState() throws Exception {
		try (var database = new DeploymentDatabaseIT.ProbeDatabase()) {
			Path runtime = directory("runtime");
			Path secrets = directory("secrets");
			Path logs = directory("logs");
			Path password = privateFile(secrets.resolve("viewer-password"), PASSWORD);
			Path output = secrets.resolve("offline-hash");
			Path firstLog = log();
			Process app = launch(runtime, password, logs, firstLog, database.environment(), "serve");
			try {
				int port = awaitReady(app, firstLog, runtime, database.environment());
				assertFalse(Files.exists(password));
				assertTrue(Files.isRegularFile(runtime.resolve(FirstStartProvisioner.INITIALIZED)));
				assertFalse(Files.exists(runtime.resolve(FirstStartProvisioner.INITIALIZING)));
				assertFalse(Files.exists(runtime.resolve(".intruvia-bootstrap")));
				Path config = runtime.resolve("config");
				assertEquals(RuntimeLock.DIRECTORY_PERMISSIONS, Files.getPosixFilePermissions(config));
				for (String name : List.of("StrolchConfiguration.xml", "PrivilegeConfig.xml", "PrivilegeRoles.xml",
						"PrivilegeUsers.xml", "viewer.properties", "ingestion.properties", "machine-identities.conf"))
					assertEquals(name, RuntimeLock.FILE_PERMISSIONS, Files.getPosixFilePermissions(config.resolve(name)));
				String strolch = Files.readString(config.resolve("StrolchConfiguration.xml"));
				for (String flag : List.of("allowSchemaCreation", "allowSchemaMigration", "allowSchemaDrop",
						"runMigrationsOnStart", "pollMigrations"))
					assertTrue(flag, strolch.contains("<" + flag + ">false</" + flag + ">"));
				assertEquals("origin=" + ORIGIN + "\n", Files.readString(config.resolve("viewer.properties")));
				String[] secretsValues = privilegeSecrets(config.resolve("PrivilegeConfig.xml"));
				assertNotEquals(secretsValues[0], secretsValues[1]);
				assertEquals(64, secretsValues[0].length());
				assertEquals(64, secretsValues[1].length());
				String users = Files.readString(config.resolve("PrivilegeUsers.xml"));
				assertFalse(users.contains(PASSWORD));
				assertTrue(users.contains("username=\"viewer\""));
				assertTrue(users.contains("<State>ENABLED</State>"));
				assertTrue(users.contains("<Role>Viewer</Role>"));
				login(port);
				database.execute("CREATE TABLE public.provisioning_test (value integer)");
				database.execute("INSERT INTO public.provisioning_test VALUES (42)");
				Path offlinePassword = privateFile(secrets.resolve("offline-password"), PASSWORD);
				Path blockedLog = log();
				Process blocked = launch(runtime, password, logs, blockedLog, database.environment(),
						"offline", "hash", offlinePassword.toString(), output.toString());
				assertTrue(blocked.waitFor(30, TimeUnit.SECONDS));
				assertNotEquals(0, blocked.exitValue());
				assertFalse(Files.exists(output));
				assertNoSecrets(blockedLog, database.environment(), secretsValues);
				assertNoSecrets(firstLog, database.environment(), secretsValues);
			} finally {
				stop(app);
			}
			Path offlinePassword = secrets.resolve("offline-password");
			Path offlineLog = log();
			Process offline = launch(runtime, password, logs, offlineLog, database.environment(),
					"offline", "hash", offlinePassword.toString(), output.toString());
			assertTrue(offline.waitFor(60, TimeUnit.SECONDS));
			assertEquals(0, offline.exitValue());
			assertEquals(RuntimeLock.FILE_PERMISSIONS, Files.getPosixFilePermissions(output));
			assertFalse(Files.readString(output).contains(PASSWORD));
			byte[] privilegeConfig = Files.readAllBytes(runtime.resolve("config/PrivilegeConfig.xml"));
			byte[] users = Files.readAllBytes(runtime.resolve("config/PrivilegeUsers.xml"));

			Path otherRuntime = directory("other-runtime");
			Path otherPassword = privateFile(secrets.resolve("other-password"), PASSWORD);
			try (var lock = RuntimeLock.acquire(otherRuntime)) {
				assertThrows(ConfigurationFailure.class, () -> FirstStartProvisioner.ensureInitialized(lock, otherRuntime,
						templates(), otherPassword, ORIGIN, database.environment(), jar()));
			}
			assertFalse(Files.exists(otherRuntime.resolve(FirstStartProvisioner.INITIALIZING)));
			assertFalse(Files.exists(otherRuntime.resolve("config")));
			assertEquals(PASSWORD, Files.readString(otherPassword));
			assertEquals(42, database.integer("SELECT value FROM public.provisioning_test"));

			privateFile(password, "preserve-new-password-file");
			Path restartLog = log();
			Process restart = launch(runtime, password, logs, restartLog, database.environment(), "serve");
			try {
				int port = awaitReady(restart, restartLog, runtime, database.environment());
				assertArrayEquals(privilegeConfig, Files.readAllBytes(runtime.resolve("config/PrivilegeConfig.xml")));
				assertArrayEquals(users, Files.readAllBytes(runtime.resolve("config/PrivilegeUsers.xml")));
				assertEquals("preserve-new-password-file", Files.readString(password));
				assertEquals(42, database.integer("SELECT value FROM public.provisioning_test"));
				login(port);
				assertNoSecrets(restartLog, database.environment(), privilegeSecrets(runtime.resolve("config/PrivilegeConfig.xml")));
			} finally {
				stop(restart);
			}
			byte[] savedUsers = Files.readAllBytes(runtime.resolve("config/PrivilegeUsers.xml"));
			Files.move(runtime.resolve(FirstStartProvisioner.INITIALIZED), runtime.resolve(FirstStartProvisioner.INITIALIZING),
					StandardCopyOption.ATOMIC_MOVE);
			Path interruptedLog = log();
			Process interrupted = launch(runtime, password, logs, interruptedLog, database.environment(), "serve");
			assertTrue(interrupted.waitFor(30, TimeUnit.SECONDS));
			assertNotEquals(0, interrupted.exitValue());
			assertArrayEquals(savedUsers, Files.readAllBytes(runtime.resolve("config/PrivilegeUsers.xml")));
			assertEquals(42, database.integer("SELECT value FROM public.provisioning_test"));
		}
	}

	@Test(timeout = 60000)
	public void failedStagingLeavesMarkerAndDoesNotTouchTheDatabaseOrPassword() throws Exception {
		try (var database = new DeploymentDatabaseIT.ProbeDatabase()) {
			Path runtime = directory("runtime");
			Path password = privateFile(directory("secrets").resolve("viewer-password"), PASSWORD);
			try (var lock = RuntimeLock.acquire(runtime)) {
				assertThrows(ConfigurationFailure.class, () -> FirstStartProvisioner.ensureInitialized(lock, runtime,
						runtime.resolve("missing-templates"), password, ORIGIN, database.environment(), jar()));
				assertTrue(Files.exists(runtime.resolve(FirstStartProvisioner.INITIALIZING)));
				assertThrows(ConfigurationFailure.class, () -> FirstStartProvisioner.ensureInitialized(lock, runtime,
						templates(), password, ORIGIN, database.environment(), jar()));
			}
			DeploymentDatabase.requireEmpty(database.environment());
			assertEquals(PASSWORD, Files.readString(password));
			assertFalse(Files.exists(runtime.resolve(FirstStartProvisioner.INITIALIZED)));
		}
	}

	private Process launch(Path runtime, Path password, Path logs, Path log, Map<String, String> database, String... arguments) throws Exception {
		String classpath = jar() + File.pathSeparator + jar().getParent().resolve("lib/*");
		var command = new ArrayList<>(List.of("/bin/sh", "-c", "umask 077; exec \"$@\"", "intruvia-test",
				Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Dintruvia.log.dir=" + logs,
				"-cp", classpath, DeploymentEntrypoint.class.getName(), runtime.toString(), templates().toString(), "0"));
		command.addAll(List.of(arguments));
		var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
		builder.environment().putAll(database);
		builder.environment().put("INTRUVIA_PUBLIC_ORIGIN", ORIGIN);
		builder.environment().put("INTRUVIA_VIEWER_PASSWORD_FILE", password.toString());
		builder.environment().put("INTRUVIA_BIND_ADDRESS", "127.0.0.1");
		return builder.start();
	}

	private static int awaitReady(Process process, Path log, Path runtime, Map<String, String> database) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
		try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
			while (System.nanoTime() < deadline) {
				if (!process.isAlive()) throw new AssertionError("Container entrypoint exited: " + safeLog(log, runtime, database));
				var matcher = STARTED.matcher(Files.readString(log));
				if (matcher.find()) {
					int port = Integer.parseInt(matcher.group(1));
					try {
						var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health/ready"))
								.timeout(Duration.ofSeconds(2)).GET().build();
						if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) return port;
					} catch (java.io.IOException ignored) { }
				}
				Thread.sleep(100);
			}
		}
		throw new AssertionError("Container entrypoint readiness timed out: " + safeLog(log, runtime, database));
	}

	private static String safeLog(Path log, Path runtime, Map<String, String> database) throws Exception {
		String text = Files.readString(log);
		Path bootstrap = runtime.resolve(".intruvia-bootstrap");
		if (Files.isDirectory(bootstrap)) {
			try (var files = Files.list(bootstrap)) {
				for (Path file : files.filter(path -> path.getFileName().toString().startsWith("schema-bootstrap-")).toList())
					text += "\n" + Files.readString(file);
			}
		}
		text = text.replace(PASSWORD, "[redacted]").replace(database.get("DB_PASSWORD"), "[redacted]")
				.replace(database.get("DB_URL"), "[redacted]");
		Path config = runtime.resolve("config/PrivilegeConfig.xml");
		if (Files.isRegularFile(config))
			for (String secret : privilegeSecrets(config)) text = text.replace(secret, "[redacted]");
		return text.substring(Math.max(0, text.length() - 4000));
	}

	private static void login(int port) throws Exception {
		try (var client = HttpClient.newHttpClient()) {
			var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/session"))
					.header("Origin", ORIGIN).header("X-Intruvia-CSRF", "1").header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"viewer\",\"password\":\"" + PASSWORD + "\"}"))
					.build();
			assertEquals(200, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
		}
	}

	private static void stop(Process process) throws Exception {
		if (!process.isAlive()) return;
		process.destroy();
		if (!process.waitFor(120, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			process.waitFor();
			fail("Application did not stop cleanly");
		}
		assertEquals(143, process.exitValue());
	}

	private static String[] privilegeSecrets(Path file) throws Exception {
		var parameters = ProvisioningFiles.xml(Files.readAllBytes(file)).getElementsByTagName("Parameter");
		String[] values = new String[2];
		for (int i = 0; i < parameters.getLength(); i++) {
			Element parameter = (Element) parameters.item(i);
			if (parameter.getAttribute("name").equals("secretKey")) values[0] = parameter.getAttribute("value");
			if (parameter.getAttribute("name").equals("secretSalt")) values[1] = parameter.getAttribute("value");
		}
		assertNotNull(values[0]);
		assertNotNull(values[1]);
		return values;
	}

	private static void assertNoSecrets(Path log, Map<String, String> database, String[] secrets) throws Exception {
		String text = Files.readString(log, StandardCharsets.UTF_8);
		assertFalse(text.contains(PASSWORD));
		assertFalse(text.contains(database.get("DB_PASSWORD")));
		for (String secret : secrets) assertFalse(text.contains(secret));
	}

	private Path directory(String name) throws Exception {
		Path path = temporary.newFolder(name).toPath();
		Files.setPosixFilePermissions(path, RuntimeLock.DIRECTORY_PERMISSIONS);
		return path;
	}

	private static Path privateFile(Path path, String value) throws Exception {
		Files.writeString(path, value);
		Files.setPosixFilePermissions(path, RuntimeLock.FILE_PERMISSIONS);
		return path;
	}

	private Path log() throws Exception {
		Path path = temporary.getRoot().toPath().resolve("process-" + this.logNumber++ + ".log");
		return Files.createFile(path, PosixFilePermissions.asFileAttribute(RuntimeLock.FILE_PERMISSIONS));
	}

	private static Path jar() { return Path.of("target/intruvia/intruvia-app-0.0.1.jar").toAbsolutePath().normalize(); }
	private static Path templates() { return Path.of("src/main/runtime").toAbsolutePath().normalize(); }
}