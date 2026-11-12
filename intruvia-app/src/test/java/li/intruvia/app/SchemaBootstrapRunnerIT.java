// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class SchemaBootstrapRunnerIT {

	private static final String CANARY = "bootstrap-secret-canary-42f3bc";
	private static final String FAILURE = "Schema bootstrap failed; preserve the protected runtime and follow recovery instructions";
	private static final Path JAR = Path.of("target/intruvia/intruvia-app-0.0.1.jar").toAbsolutePath();
	private DatabaseFixture database;

	@Before
	public void createDatabase() throws Exception {
		this.database = new DatabaseFixture();
	}

	@After
	public void dropDatabase() throws Exception {
		if (this.database != null) this.database.close();
	}

	@Test(timeout = 180000)
	public void packagedApplicationBootstrapsOnLoopbackAndStopsCleanly() throws Exception {
		Map<String, String> environment = productionEnvironment();
		environment.put("INTRUVIA_BIND_ADDRESS", "0.0.0.0");
		environment.put("VIEWER_PASSWORD", CANARY);
		environment.put("INTRUVIA_VIEWER_PASSWORD_FILE", CANARY);
		environment.put("JAVA_TOOL_OPTIONS", "-Dbootstrap.canary=" + CANARY);
		environment.put("DB_POOL_maximumPoolSize", "2");
		SchemaBootstrapRunner.run(this.database.runtime, JAR, environment);
		Path log = log();
		assertEquals(RuntimeLock.FILE_PERMISSIONS, Files.getPosixFilePermissions(log));
		String output = Files.readString(log);
		assertTrue(output.matches("(?s).*Intruvia started on 127\\.0\\.0\\.1:[1-9][0-9]* \\(Strolch STARTED\\).*"));
		assertTrue(output.contains("Intruvia stopped: Jetty STOPPED; Strolch DESTROYED"));
		assertFalse(output.contains(CANARY));
		assertFalse(output.contains("Application shutdown failed"));
		try (var connection = this.database.faultConnection(); var statement = connection.createStatement();
				var rows = statement.executeQuery("SELECT to_regclass('public.resources') IS NOT NULL")) {
			assertTrue(rows.next());
			assertTrue(rows.getBoolean(1));
		}
	}

	@Test(timeout = 60000)
	public void failedPackagedStartupHasFixedErrorAndProtectedLog() throws Exception {
		Map<String, String> environment = productionEnvironment();
		environment.put("DB_PASSWORD", CANARY);
		environment.put("DB_POOL_connectionTimeout", "1000");
		environment.put("DB_POOL_validationTimeout", "500");
		try {
			SchemaBootstrapRunner.run(this.database.runtime, JAR, environment);
			fail("Expected schema bootstrap to fail");
		} catch (ConfigurationFailure e) {
			assertEquals(FAILURE, e.getMessage());
			assertNull(e.getCause());
			assertFalse(e.toString().contains(CANARY));
		}
		Path log = log();
		assertEquals(RuntimeLock.FILE_PERMISSIONS, Files.getPosixFilePermissions(log));
		assertFalse(Files.readString(log).contains(CANARY));
		assertFalse(Files.readString(log).contains("Intruvia started on 127.0.0.1:"));
	}

	@Test(timeout = 60000)
	public void invalidPoolSettingHasFixedErrorWithoutStartingChild() throws Exception {
		Map<String, String> environment = productionEnvironment();
		environment.put("DB_POOL_maximumPoolSize", CANARY);
		try {
			SchemaBootstrapRunner.run(this.database.runtime, JAR, environment);
			fail("Expected invalid pool setting to fail");
		} catch (ConfigurationFailure e) {
			assertEquals(FAILURE, e.getMessage());
			assertNull(e.getCause());
			assertFalse(e.toString().contains(CANARY));
		}
		Path log = log();
		assertEquals(RuntimeLock.FILE_PERMISSIONS, Files.getPosixFilePermissions(log));
		assertEquals(0, Files.size(log));
	}

	@Test(timeout = 60000)
	public void interruptTerminatesChildAndPreservesInterruptStatus() throws Exception {
		Map<String, String> environment = productionEnvironment();
		environment.put("VIEWER_PASSWORD", CANARY);
		environment.put("INTRUVIA_VIEWER_PASSWORD_FILE", CANARY);
		environment.put("JAVA_TOOL_OPTIONS", "-Dbootstrap.canary=" + CANARY);
		environment.put("INTRUVIA_BIND_ADDRESS", "0.0.0.0");
		environment.put("DB_POOL_maximumPoolSize", "2");
		AtomicReference<Throwable> result = new AtomicReference<>();
		AtomicBoolean interrupted = new AtomicBoolean();
		Thread runner = new Thread(() -> {
			try {
				SchemaBootstrapRunner.run(this.database.runtime, JAR, environment);
			} catch (Throwable e) {
				result.set(e);
			} finally {
				interrupted.set(Thread.currentThread().isInterrupted());
			}
		}, "bootstrap-test-runner");
		runner.start();
		ProcessHandle child = null;
		try {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
			while (child == null && runner.isAlive() && System.nanoTime() < deadline) {
				try (var processes = ProcessHandle.current().descendants()) {
					child = processes.filter(process -> Arrays.stream(process.info().arguments().orElse(new String[0]))
							.anyMatch(this.database.runtime.toString()::equals)).findFirst().orElse(null);
				}
				if (child == null) Thread.sleep(10);
			}
			assertNotNull("Bootstrap child did not start", child);
			assertTrue("Unexpected bootstrap child arguments", Arrays.equals(new String[]{"-jar", JAR.toString(),
					this.database.runtime.toString(), "0"}, child.info().arguments().orElse(new String[0])));
			String childEnvironment = "";
			while (child.isAlive() && System.nanoTime() < deadline) {
				childEnvironment = Files.readString(Path.of("/proc", Long.toString(child.pid()), "environ"));
				if (childEnvironment.contains("INTRUVIA_BIND_ADDRESS=127.0.0.1\0")) break;
				Thread.sleep(10);
			}
			Set<String> keys = Arrays.stream(childEnvironment.split("\0"))
					.filter(entry -> entry.contains("="))
					.map(entry -> entry.substring(0, entry.indexOf('='))).collect(Collectors.toSet());
			assertEquals(Set.of("DB_URL", "DB_USERNAME", "DB_PASSWORD", "DB_POOL_maximumPoolSize", "INTRUVIA_BIND_ADDRESS"), keys);
			for (String key : new String[]{"DB_URL", "DB_USERNAME", "DB_PASSWORD", "DB_POOL_maximumPoolSize"})
				assertTrue("Bootstrap child is missing " + key, childEnvironment.contains(key + "=" + environment.get(key) + "\0"));
			assertTrue(childEnvironment.contains("INTRUVIA_BIND_ADDRESS=127.0.0.1\0"));
			assertFalse(childEnvironment.contains("VIEWER_PASSWORD="));
			assertFalse(childEnvironment.contains("INTRUVIA_VIEWER_PASSWORD_FILE="));
			assertFalse(childEnvironment.contains("JAVA_TOOL_OPTIONS="));
			assertFalse(childEnvironment.contains(CANARY));
			runner.interrupt();
			runner.join(TimeUnit.SECONDS.toMillis(20));
			assertFalse("Interrupted runner did not finish", runner.isAlive());
			assertFalse("Interrupted bootstrap child is still alive", child.isAlive());
			assertTrue(result.get() instanceof ConfigurationFailure);
			assertEquals(FAILURE, result.get().getMessage());
			assertNull(result.get().getCause());
			assertTrue(interrupted.get());
			assertFalse(Files.readString(log()).contains(CANARY));
		} finally {
			if (runner.isAlive()) {
				runner.interrupt();
				runner.join(TimeUnit.SECONDS.toMillis(20));
			}
		}
	}

	@Test(timeout = 60000)
	public void parentShutdownTerminatesChild() throws Exception {
		Path parentLog = Files.createTempFile(this.database.runtime, "parent-", ".log",
				PosixFilePermissions.asFileAttribute(RuntimeLock.FILE_PERMISSIONS));
		Process parent = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), ShutdownProbe.class.getName(),
				this.database.runtime.toString(), JAR.toString()).redirectErrorStream(true).redirectOutput(parentLog.toFile()).start();
		ProcessHandle child = null;
		try {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
			while (child == null && parent.isAlive() && System.nanoTime() < deadline) {
				try (var processes = parent.descendants()) {
					child = processes.filter(process -> Arrays.stream(process.info().arguments().orElse(new String[0]))
							.anyMatch(this.database.runtime.toString()::equals)).findFirst().orElse(null);
				}
				if (child == null) Thread.sleep(10);
			}
			assertNotNull("Bootstrap child did not start", child);
			parent.destroy();
			assertTrue("Parent did not finish shutting down", parent.waitFor(30, TimeUnit.SECONDS));
			assertFalse("Bootstrap child survived parent shutdown", child.isAlive());
			assertFalse(Files.readString(parentLog).contains(CANARY));
			assertFalse(Files.readString(log()).contains(CANARY));
		} finally {
			if (child != null && child.isAlive()) child.destroyForcibly();
			if (parent.isAlive()) parent.destroyForcibly();
			if (child != null) child.onExit().get(5, TimeUnit.SECONDS);
			parent.waitFor(5, TimeUnit.SECONDS);
		}
	}

	public static final class ShutdownProbe {
		public static void main(String[] args) {
			SchemaBootstrapRunner.run(Path.of(args[0]), Path.of(args[1]), Map.of("VIEWER_PASSWORD", CANARY));
		}
	}

	private Map<String, String> productionEnvironment() throws Exception {
		Map<String, String> environment = new HashMap<>(this.database.productionEnvironment());
		String config = Files.readString(Path.of("src/main/runtime/config/StrolchConfiguration.xml"))
				.replace("<allowSchemaCreation>false", "<allowSchemaCreation>true")
				.replace("<databasePath></databasePath>",
						"<databasePath>" + this.database.runtime.resolve("missing.mmdb") + "</databasePath>");
		Files.writeString(this.database.runtime.resolve("config/StrolchConfiguration.xml"), config);
		return environment;
	}

	private Path log() throws Exception {
		try (var paths = Files.list(this.database.runtime)) {
			return paths.filter(path -> path.getFileName().toString().startsWith("schema-bootstrap-"))
					.findFirst().orElseThrow();
		}
	}
}