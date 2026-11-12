// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

final class RuntimeLock implements AutoCloseable {
	static final String FILE = ".intruvia.lock";
	static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
	static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");
	private static final Set<PosixFilePermission> READ_ONLY_PERMISSIONS = PosixFilePermissions.fromString("r--------");
	private final Path runtime;
	private final FileChannel channel;
	private final FileLock lock;

	private RuntimeLock(Path runtime, FileChannel channel, FileLock lock) {
		this.runtime = runtime;
		this.channel = channel;
		this.lock = lock;
	}

	static RuntimeLock acquire(Path runtime) {
		FileChannel channel = null;
		try {
			runtime = runtime.toAbsolutePath().normalize();
			requireDirectory(runtime);
			Path path = runtime.resolve(FILE);
			try {
				Files.createFile(path, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
			} catch (FileAlreadyExistsException ignored) {
				// An existing lock file is expected on restart.
			}
			requireFile(path);
			if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(FILE_PERMISSIONS))
				throw failure();
			channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
			FileLock lock = channel.tryLock();
			if (lock == null) throw failure();
			return new RuntimeLock(runtime, channel, lock);
		} catch (IOException | OverlappingFileLockException | UnsupportedOperationException | SecurityException e) {
			if (channel != null) try { channel.close(); } catch (IOException ignored) { }
			throw failure();
		} catch (ConfigurationFailure e) {
			if (channel != null) try { channel.close(); } catch (IOException ignored) { }
			throw e;
		}
	}

	boolean holds(Path path) {
		return this.lock.isValid() && this.runtime.equals(path.toAbsolutePath().normalize());
	}

	static void requireDirectory(Path path) throws IOException {
		if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path) ||
				!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(DIRECTORY_PERMISSIONS) || !ownedByProcess(path))
			throw failure();
	}

	static void requireFile(Path path) throws IOException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path) || !ownedByProcess(path))
			throw failure();
		var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
		if (!permissions.equals(FILE_PERMISSIONS) && !permissions.equals(READ_ONLY_PERMISSIONS)) throw failure();
	}

	private static boolean ownedByProcess(Path path) throws IOException {
		return Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS)
				.equals(Files.getAttribute(Path.of("/proc/self"), "unix:uid"));
	}

	private static ConfigurationFailure failure() {
		return new ConfigurationFailure("Runtime is locked or paths are not owner-only; preserve the runtime and follow recovery instructions");
	}

	@Override
	public void close() throws IOException {
		try {
			this.lock.release();
		} finally {
			this.channel.close();
		}
	}
}