// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

final class StartupFailureLog {
	private static final int MAX_CAUSES = 4;
	private static final int MAX_FRAMES = 24;
	private static final long MAX_LOG_BYTES = 1_048_576;

	private StartupFailureLog() {
	}

	static Path path() {
		String directory = System.getProperty("intruvia.log.dir", "/var/log/intruvia");
		return Path.of(directory).toAbsolutePath().normalize().resolve("startup-failures.log");
	}

	static synchronized void record(StartupFailure failure) {
		if (!failure.markLogged()) return;
		try {
			Path log = path();
			Path directory = log.getParent();
			RuntimeLock.requireDirectory(directory);
			byte[] record = format(failure).getBytes(StandardCharsets.UTF_8);
			rotateIfFull(log, record.length);
			createIfMissing(log);
			RuntimeLock.requireFile(log);
			try (FileChannel channel = FileChannel.open(log, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
					LinkOption.NOFOLLOW_LINKS)) {
				ByteBuffer buffer = ByteBuffer.wrap(record);
				while (buffer.hasRemaining()) {
					if (channel.write(buffer) <= 0) throw new IOException("Startup diagnostics write made no progress");
				}
				channel.force(true);
			}
		} catch (IOException | RuntimeException e) {
			System.err.println("Unable to write startup diagnostics to the protected application log; details are unavailable");
		}
	}

	private static void rotateIfFull(Path log, int recordLength) throws IOException {
		if (!Files.exists(log, LinkOption.NOFOLLOW_LINKS)) return;
		RuntimeLock.requireFile(log);
		if (Files.size(log) + recordLength <= MAX_LOG_BYTES) return;
		Path previous = log.resolveSibling(log.getFileName() + ".1");
		if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) RuntimeLock.requireFile(previous);
		Files.move(log, previous, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void createIfMissing(Path log) throws IOException {
		try {
			Files.createFile(log, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(RuntimeLock.FILE_PERMISSIONS));
		} catch (java.nio.file.FileAlreadyExistsException ignored) {
			// An existing file is expected on restart.
		}
	}

	private static String format(StartupFailure failure) {
		StringBuilder record = new StringBuilder()
				.append(DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
				.append(" ERROR stage=").append(safe(failure.stage()))
				.append(" type=").append(failure.getClass().getName())
				.append(" detail=").append(safe(failure.getMessage())).append('\n');
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		Throwable cause = failure;
		for (int depth = 0; cause != null && depth < MAX_CAUSES && visited.add(cause); depth++, cause = StartupFailure.nextCause(cause)) {
			if (cause != failure)
				record.append("  cause[").append(depth).append("]=").append(cause.getClass().getName()).append('\n');
			StackTraceElement[] frames = cause.getStackTrace();
			for (int i = 0; i < Math.min(frames.length, MAX_FRAMES); i++)
				record.append("  at ").append(frames[i].getClassName()).append('.')
						.append(frames[i].getMethodName()).append('(').append(frames[i].getFileName())
						.append(':').append(frames[i].getLineNumber()).append(")\n");
		}
		return record.append('\n').toString();
	}

	private static String safe(String value) {
		if (value == null) return "unspecified";
		return value.replaceAll("\\p{Cntrl}", " ").strip();
	}
}
