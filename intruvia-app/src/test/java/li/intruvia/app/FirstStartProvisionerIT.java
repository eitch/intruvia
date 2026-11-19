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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class FirstStartProvisionerIT {
	private static final String PASSWORD = "viewer-provisioning-canary";
	private static final String ORIGIN = "https://intruvia.example.org";
	private static final Pattern STARTED = Pattern.compile("Intruvia started on 127\\.0\\.0\\.1:(\\d+)");
	private static final Pattern INITIAL_PASSWORD = Pattern.compile(
			"Initial viewer password \\(shown once; save it now\\):\\R([0-9a-f]{64})\\R");
	private static final Pattern REPLACEMENT_PASSWORD = Pattern.compile(
			"Replacement viewer password \\(shown once; save it now\\):\\R([0-9a-f]{64})\\R");
	@Rule public TemporaryFolder temporary = new TemporaryFolder();
	private int logNumber;

	@Test(timeout = 240000)
	public void freshInitializationLoginOfflineLockAndRestartPreserveState() throws Exception {
		try (var database = new DeploymentDatabaseIT.ProbeDatabase()) {
			Path runtime = directory("runtime");
			Path secrets = directory("secrets");
			Path logs = directory("logs");
			Path legacyPassword = secrets.resolve("viewer-password");
			Path output = secrets.resolve("offline-hash");
			Path firstLog = log();
			String initialPassword;
			Process app = launch(runtime, logs, firstLog, database.environment(), "serve");
			try {
				int port = awaitReady(app, firstLog, runtime, database.environment());
				initialPassword = password(firstLog, INITIAL_PASSWORD);
				assertFalse(Files.exists(legacyPassword));
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
				assertFalse(users.contains(initialPassword));
				assertTrue(users.contains("username=\"viewer\""));
				assertTrue(users.contains("<State>ENABLED</State>"));
				assertTrue(users.contains("<Role>Viewer</Role>"));
				assertRuntimeDoesNotContain(runtime, initialPassword);
				assertFalse(Files.readString(Path.of("/proc", Long.toString(app.pid()), "cmdline"), StandardCharsets.UTF_8)
						.contains(initialPassword));
				assertFalse(Files.readString(Path.of("/proc", Long.toString(app.pid()), "environ"), StandardCharsets.UTF_8)
						.contains(initialPassword));
				login(port, initialPassword);
				database.execute("CREATE TABLE public.provisioning_test (value integer)");
				database.execute("INSERT INTO public.provisioning_test VALUES (42)");
				Path offlinePassword = privateFile(secrets.resolve("offline-password"), PASSWORD);
				Path blockedLog = log();
				Process blocked = launch(runtime, logs, blockedLog, database.environment(),
						"offline", "hash", offlinePassword.toString(), output.toString());
				assertTrue(blocked.waitFor(30, TimeUnit.SECONDS));
				assertNotEquals(0, blocked.exitValue());
				assertFalse(Files.exists(output));
				assertNoSecrets(blockedLog, database.environment(), secretsValues);
				assertNoSecrets(firstLog, database.environment(), secretsValues);
				byte[] usersBeforeBlockedReset = Files.readAllBytes(config.resolve("PrivilegeUsers.xml"));
				Path blockedResetLog = log();
				Process blockedReset = launch(runtime, logs, blockedResetLog, database.environment(),
						"offline", "reset-viewer-password");
				assertTrue(blockedReset.waitFor(30, TimeUnit.SECONDS));
				assertNotEquals(0, blockedReset.exitValue());
				assertArrayEquals(usersBeforeBlockedReset, Files.readAllBytes(config.resolve("PrivilegeUsers.xml")));
				assertPasswordNotPrinted(blockedResetLog);
			} finally {
				stop(app);
			}
			Path offlinePassword = secrets.resolve("offline-password");
			Path offlineLog = log();
			Process offline = launch(runtime, logs, offlineLog, database.environment(),
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
						templates(), ORIGIN, database.environment(), jar()));
			}
			assertFalse(Files.exists(otherRuntime.resolve(FirstStartProvisioner.INITIALIZING)));
			assertFalse(Files.exists(otherRuntime.resolve("config")));
			assertEquals(PASSWORD, Files.readString(otherPassword));
			assertEquals(42, database.integer("SELECT value FROM public.provisioning_test"));

			privateFile(legacyPassword, "preserve-new-password-file");
			Path restartLog = log();
			Process restart = launch(runtime, logs, restartLog, database.environment(), legacyPassword, "serve");
			try {
				int port = awaitReady(restart, restartLog, runtime, database.environment());
				assertArrayEquals(privilegeConfig, Files.readAllBytes(runtime.resolve("config/PrivilegeConfig.xml")));
				assertArrayEquals(users, Files.readAllBytes(runtime.resolve("config/PrivilegeUsers.xml")));
				assertEquals("preserve-new-password-file", Files.readString(legacyPassword));
				assertEquals(42, database.integer("SELECT value FROM public.provisioning_test"));
				assertPasswordNotPrinted(restartLog);
				login(port, initialPassword);
				assertNoSecrets(restartLog, database.environment(), privilegeSecrets(runtime.resolve("config/PrivilegeConfig.xml")));
			} finally {
				stop(restart);
			}
			Path usersFile = runtime.resolve("config/PrivilegeUsers.xml");
			byte[] usersBeforeReset = Files.readAllBytes(usersFile);
			Path resetLog = log();
			Process reset = launch(runtime, logs, resetLog, database.environment(), "offline", "reset-viewer-password");
			assertTrue(reset.waitFor(60, TimeUnit.SECONDS));
			assertEquals(safeLog(resetLog, runtime, database.environment()), 0, reset.exitValue());
			String replacementPassword = password(resetLog, REPLACEMENT_PASSWORD);
			assertFalse(java.util.Arrays.equals(usersBeforeReset, Files.readAllBytes(usersFile)));
			assertRuntimeDoesNotContain(runtime, initialPassword, replacementPassword);
			assertFalse(Files.readString(usersFile).contains(initialPassword));
			assertFalse(Files.readString(usersFile).contains(replacementPassword));
			Path afterResetLog = log();
			Process afterReset = launch(runtime, logs, afterResetLog, database.environment(), "serve");
			try {
				int port = awaitReady(afterReset, afterResetLog, runtime, database.environment());
				assertPasswordNotPrinted(afterResetLog);
				assertNotEquals(200, loginStatus(port, initialPassword));
				assertEquals(200, loginStatus(port, replacementPassword));
				assertEquals(42, database.integer("SELECT value FROM public.provisioning_test"));
			} finally {
				stop(afterReset);
			}
			byte[] savedUsers = Files.readAllBytes(runtime.resolve("config/PrivilegeUsers.xml"));
			Files.move(runtime.resolve(FirstStartProvisioner.INITIALIZED), runtime.resolve(FirstStartProvisioner.INITIALIZING),
					StandardCopyOption.ATOMIC_MOVE);
			Path interruptedLog = log();
			Process interrupted = launch(runtime, logs, interruptedLog, database.environment(), "serve");
			assertTrue(interrupted.waitFor(30, TimeUnit.SECONDS));
			assertNotEquals(0, interrupted.exitValue());
			assertArrayEquals(savedUsers, Files.readAllBytes(runtime.resolve("config/PrivilegeUsers.xml")));
			assertEquals(42, database.integer("SELECT value FROM public.provisioning_test"));
		}
	}

	@Test(timeout = 60000)
	public void failedStagingLeavesMarkerAndDoesNotTouchTheDatabaseOrLegacyPassword() throws Exception {
		try (var database = new DeploymentDatabaseIT.ProbeDatabase()) {
			Path runtime = directory("runtime");
			Path password = privateFile(directory("secrets").resolve("viewer-password"), PASSWORD);
			try (var lock = RuntimeLock.acquire(runtime)) {
				assertThrows(ConfigurationFailure.class, () -> FirstStartProvisioner.ensureInitialized(lock, runtime,
						runtime.resolve("missing-templates"), ORIGIN, database.environment(), jar()));
				assertTrue(Files.exists(runtime.resolve(FirstStartProvisioner.INITIALIZING)));
				assertThrows(ConfigurationFailure.class, () -> FirstStartProvisioner.ensureInitialized(lock, runtime,
						templates(), ORIGIN, database.environment(), jar()));
			}
			DeploymentDatabase.requireEmpty(database.environment());
			assertEquals(PASSWORD, Files.readString(password));
			assertFalse(Files.exists(runtime.resolve(FirstStartProvisioner.INITIALIZED)));
		}
	}

	@Test(timeout = 60000)
	public void entrypointPersistsSafeOriginAndDatabaseFailureDiagnostics() throws Exception {
		try (var database = new DeploymentDatabaseIT.ProbeDatabase()) {
			var invalidOrigin = new java.util.HashMap<>(database.environment());
			invalidOrigin.put("INTRUVIA_PUBLIC_ORIGIN", "http://" + PASSWORD + "@localhost");
			checkStartupFailure(directory("invalid-origin-runtime"), directory("invalid-origin-logs"), invalidOrigin,
					"public origin validation", "INTRUVIA_PUBLIC_ORIGIN");
			var invalidDatabase = new java.util.HashMap<>(database.environment());
			invalidDatabase.put("DB_PASSWORD", PASSWORD);
			checkStartupFailure(directory("invalid-database-runtime"), directory("invalid-database-logs"), invalidDatabase,
					"database empty-state verification", "PostgreSQL authentication failed; verify DB_USERNAME and DB_PASSWORD " +
							"(INTRUVIA_DB_USERNAME and INTRUVIA_DB_PASSWORD in Compose) match the credentials stored in this database");
			DeploymentDatabase.requireEmpty(database.environment());
		}
	}

	private void checkStartupFailure(Path runtime, Path logs, Map<String, String> environment, String stage, String detail)
			throws Exception {
		Path console = log();
		Process process = launch(runtime, logs, console, environment, "serve");
		assertTrue(process.waitFor(30, TimeUnit.SECONDS));
		assertNotEquals(0, process.exitValue());
		assertFalse(Files.exists(runtime.resolve(FirstStartProvisioner.INITIALIZING)));
		assertFalse(Files.exists(runtime.resolve(FirstStartProvisioner.INITIALIZED)));
		Path diagnostic = logs.resolve("startup-failures.log");
		assertEquals(RuntimeLock.FILE_PERMISSIONS, Files.getPosixFilePermissions(diagnostic));
		String text = Files.readString(diagnostic);
		assertTrue(text, text.contains("stage=" + stage));
		assertTrue(text, text.contains(detail));
		assertFalse(text, text.contains(PASSWORD));
		assertFalse(text, text.contains(environment.get("DB_PASSWORD")));
		assertFalse(Files.readString(console).contains(PASSWORD));
		assertPasswordNotPrinted(console);
	}

	private Process launch(Path runtime, Path logs, Path log, Map<String, String> database, String... arguments) throws Exception {
		return launch(runtime, logs, log, database, null, arguments);
	}

	private Process launch(Path runtime, Path logs, Path log, Map<String, String> database, Path legacyPassword,
			String... arguments) throws Exception {
		String classpath = jar() + File.pathSeparator + jar().getParent().resolve("lib/*");
		var command = new ArrayList<>(List.of("/bin/sh", "-c", "umask 077; exec \"$@\"", "intruvia-test",
				Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Dintruvia.log.dir=" + logs,
				"-cp", classpath, DeploymentEntrypoint.class.getName(), runtime.toString(), templates().toString(), "0"));
		command.addAll(List.of(arguments));
		var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
		builder.environment().putAll(database);
		builder.environment().put("INTRUVIA_PUBLIC_ORIGIN", database.getOrDefault("INTRUVIA_PUBLIC_ORIGIN", ORIGIN));
		if (legacyPassword != null) builder.environment().put("INTRUVIA_VIEWER_PASSWORD_FILE", legacyPassword.toString());
		else builder.environment().remove("INTRUVIA_VIEWER_PASSWORD_FILE");
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
		text = INITIAL_PASSWORD.matcher(text).replaceAll("Initial viewer password: [redacted]");
		text = REPLACEMENT_PASSWORD.matcher(text).replaceAll("Replacement viewer password: [redacted]");
		text = text.replace(PASSWORD, "[redacted]").replace(database.get("DB_PASSWORD"), "[redacted]")
				.replace(database.get("DB_URL"), "[redacted]");
		Path config = runtime.resolve("config/PrivilegeConfig.xml");
		if (Files.isRegularFile(config))
			for (String secret : privilegeSecrets(config)) text = text.replace(secret, "[redacted]");
		return text.substring(Math.max(0, text.length() - 4000));
	}

	private static void login(int port, String password) throws Exception {
		assertEquals(200, loginStatus(port, password));
	}

	private static int loginStatus(int port, String password) throws Exception {
		try (var client = HttpClient.newHttpClient()) {
			var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/session"))
					.header("Origin", ORIGIN).header("X-Intruvia-CSRF", "1").header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"viewer\",\"password\":\"" + password + "\"}"))
					.build();
			return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
		}
	}

	private static String password(Path log, Pattern pattern) throws Exception {
		Matcher matcher = pattern.matcher(Files.readString(log, StandardCharsets.UTF_8));
		assertTrue("one-time password output missing", matcher.find());
		String password = matcher.group(1);
		assertFalse("password output appeared more than once", matcher.find());
		return password;
	}

	private static void assertPasswordNotPrinted(Path log) throws Exception {
		String text = Files.readString(log, StandardCharsets.UTF_8);
		assertFalse(INITIAL_PASSWORD.matcher(text).find());
		assertFalse(REPLACEMENT_PASSWORD.matcher(text).find());
	}

	private static void assertRuntimeDoesNotContain(Path runtime, String... secrets) throws Exception {
		try (var files = Files.walk(runtime)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
				for (String secret : secrets) assertFalse(file.toString(), content.contains(secret));
			}
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

	private static Path jar() { return TestArtifacts.applicationJar(); }
	private static Path templates() { return Path.of("src/main/runtime").toAbsolutePath().normalize(); }
}