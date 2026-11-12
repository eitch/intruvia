// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Runs schema creation in a separate application process; the caller owns staging and recovery. */
final class SchemaBootstrapRunner {

	private static final long START_TIMEOUT_SECONDS = 120;
	private static final long STOP_TIMEOUT_SECONDS = 120;
	private static final String STOPPED = "Intruvia stopped: Jetty STOPPED; Strolch DESTROYED";
	private static final Pattern STARTED = Pattern.compile("Intruvia started on 127\\.0\\.0\\.1:(\\d{1,5}) ");

	private SchemaBootstrapRunner() {}

	static void run(Path bootstrapRuntime, Path applicationJar, Map<String, String> environment) {
		ProcessOwner owner = new ProcessOwner();
		Thread hook = new Thread(owner::shutdown, "intruvia-bootstrap-shutdown");
		boolean hookRegistered = false;
		boolean interrupted = false;
		try {
			RuntimeLock.requireDirectory(bootstrapRuntime);
			Path log = Files.createTempFile(bootstrapRuntime, "schema-bootstrap-", ".log",
					PosixFilePermissions.asFileAttribute(RuntimeLock.FILE_PERMISSIONS));
			if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
			ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
					"-jar", applicationJar.toAbsolutePath().toString(), bootstrapRuntime.toAbsolutePath().toString(), "0")
					.directory(bootstrapRuntime.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
			builder.environment().clear();
			if (environment != null) {
				for (String key : new String[]{"DB_URL", "DB_USERNAME", "DB_PASSWORD"})
					if (environment.containsKey(key)) builder.environment().put(key, environment.get(key));
			}
			copyPoolSetting(environment, builder.environment(), "DB_POOL_maximumPoolSize", 1, 256);
			copyPoolSetting(environment, builder.environment(), "DB_POOL_connectionTimeout", 250, 30000);
			copyPoolSetting(environment, builder.environment(), "DB_POOL_validationTimeout", 250, 5000);
			builder.environment().put("INTRUVIA_BIND_ADDRESS", "127.0.0.1");
			Runtime.getRuntime().addShutdownHook(hook);
			hookRegistered = true;
			Process process = owner.start(builder);
			try (RandomAccessFile output = new RandomAccessFile(log.toFile(), "r")) {
				int port = awaitPort(process, output);
				awaitReady(process, port);
				if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
				owner.requestStop();
				if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 143 ||
						!containsStopped(output))
					throw failure();
				if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
			}
		} catch (InterruptedException e) {
			interrupted = true;
			throw failure();
		} catch (IOException | RuntimeException e) {
			throw failure();
		} finally {
			interrupted |= owner.terminate(interrupted);
			if (hookRegistered) {
				try {
					Runtime.getRuntime().removeShutdownHook(hook);
				} catch (IllegalStateException ignored) {
					// The registered hook owns termination while the parent is shutting down.
				}
			}
			if (interrupted) Thread.currentThread().interrupt();
		}
	}

	private static void copyPoolSetting(Map<String, String> source, Map<String, String> target, String key, int min, int max) {
		if (source == null || !source.containsKey(key)) return;
		String value = source.get(key);
		try {
			int number = Integer.parseInt(value);
			if (number >= min && number <= max && value.matches("[0-9]+")) {
				target.put(key, value);
				return;
			}
		} catch (NumberFormatException ignored) {
			// The failure must not disclose the supplied value.
		}
		throw failure();
	}

	private static int awaitPort(Process process, RandomAccessFile output) throws IOException, InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_TIMEOUT_SECONDS);
		while (System.nanoTime() < deadline) {
			String line;
			while ((line = readLine(output)) != null) {
				if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
				if (System.nanoTime() >= deadline) throw failure();
				var matcher = STARTED.matcher(line);
				if (matcher.find()) {
					int port = Integer.parseInt(matcher.group(1));
					if (port > 0 && port <= 65535) return port;
					throw failure();
				}
			}
			if (!process.isAlive()) throw failure();
			Thread.sleep(100);
		}
		throw failure();
	}

	private static void awaitReady(Process process, int port) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_TIMEOUT_SECONDS);
		while (System.nanoTime() < deadline && process.isAlive()) {
			HttpURLConnection connection = null;
			try {
				connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/health/ready")
						.toURL().openConnection(Proxy.NO_PROXY);
				connection.setConnectTimeout(1000);
				connection.setReadTimeout(1000);
				connection.setInstanceFollowRedirects(false);
				if (connection.getResponseCode() == 200 && process.isAlive()) return;
			} catch (IOException ignored) {
				// Startup and transient readiness failures are retried until the deadline.
			} finally {
				if (connection != null) connection.disconnect();
			}
			Thread.sleep(100);
		}
		throw failure();
	}

	private static boolean containsStopped(RandomAccessFile output) throws IOException {
		String line;
		while ((line = readLine(output)) != null)
			if (line.contains(STOPPED)) return true;
		return false;
	}

	private static String readLine(RandomAccessFile output) throws IOException {
		long start = output.getFilePointer();
		byte[] bytes = new byte[8192];
		int length = 0;
		int value;
		while ((value = output.read()) != -1) {
			if (value == '\n') return new String(bytes, 0, length, StandardCharsets.UTF_8);
			bytes[length++] = (byte) value;
			if (length == bytes.length) return new String(bytes, StandardCharsets.UTF_8);
		}
		output.seek(start);
		return null;
	}

	private static ConfigurationFailure failure() {
		return new ConfigurationFailure("Schema bootstrap failed; preserve the protected runtime and follow recovery instructions");
	}

	private static final class ProcessOwner {
		private Process process;
		private boolean shuttingDown;
		private boolean stopRequested;

		synchronized Process start(ProcessBuilder builder) throws IOException {
			if (this.shuttingDown) throw failure();
			this.process = builder.start();
			return this.process;
		}

		synchronized void requestStop() {
			if (this.process != null && !this.stopRequested) {
				this.stopRequested = true;
				this.process.destroy();
			}
		}

		void shutdown() {
			synchronized (this) {
				this.shuttingDown = true;
			}
			boolean interrupted = terminate(false);
			if (interrupted) Thread.currentThread().interrupt();
		}

		boolean terminate(boolean alreadyInterrupted) {
			Process child;
			synchronized (this) { child = this.process; }
			if (child == null || !child.isAlive()) return false;
			boolean interrupted = Thread.interrupted() || alreadyInterrupted;
			requestStop();
			try {
				if (interrupted || !child.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
					child.destroyForcibly();
					interrupted |= awaitExit(child);
				}
			} catch (InterruptedException e) {
				interrupted = true;
				child.destroyForcibly();
				interrupted |= awaitExit(child);
			}
			return interrupted;
		}

		private boolean awaitExit(Process child) {
			boolean interrupted = false;
			while (child.isAlive()) {
				try { child.waitFor(); } catch (InterruptedException e) { interrupted = true; }
			}
			return interrupted;
		}
	}
}