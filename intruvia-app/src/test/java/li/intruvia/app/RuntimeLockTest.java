// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.Assert.*;

public class RuntimeLockTest {
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void holdsExclusiveLockUntilClosed() throws Exception {
		Path runtime = temporary.newFolder("runtime").toPath();
		Files.setPosixFilePermissions(runtime, RuntimeLock.DIRECTORY_PERMISSIONS);
		try (var ignored = RuntimeLock.acquire(runtime)) {
			assertThrows(ConfigurationFailure.class, () -> RuntimeLock.acquire(runtime));
			assertEquals(RuntimeLock.FILE_PERMISSIONS, Files.getPosixFilePermissions(runtime.resolve(RuntimeLock.FILE)));
		}
		try (var ignored = RuntimeLock.acquire(runtime)) {
			assertTrue(Files.isRegularFile(runtime.resolve(RuntimeLock.FILE)));
		}
	}

	@Test
	public void rejectsUnsafeDirectoriesAndSymlinkLocks() throws Exception {
		Path runtime = temporary.newFolder("runtime").toPath();
		Files.setPosixFilePermissions(runtime, PosixFilePermissions.fromString("rwxr-xr-x"));
		assertThrows(ConfigurationFailure.class, () -> RuntimeLock.acquire(runtime));
		Files.setPosixFilePermissions(runtime, RuntimeLock.DIRECTORY_PERMISSIONS);
		Path target = temporary.newFile("target").toPath();
		Files.createSymbolicLink(runtime.resolve(RuntimeLock.FILE), target);
		assertThrows(ConfigurationFailure.class, () -> RuntimeLock.acquire(runtime));
		assertEquals(0, Files.size(target));
	}
}