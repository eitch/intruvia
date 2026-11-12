// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.junit.Assert.*;

public class FirstStartProvisionerTest {
	private static final String CANARY = "private-viewer-test-canary";
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void acceptsOnlyExactHttpsOrigins() {
		FirstStartProvisioner.validateOrigin("https://intruvia.example.org");
		FirstStartProvisioner.validateOrigin("https://localhost:8443");
		for (String origin : new String[]{null, "", "http://localhost", "https://example.org/", "https://example.org?x=1",
				"https://user@example.org", "https://example.org:0", "https://*.example.org"})
			assertThrows(ConfigurationFailure.class, () -> FirstStartProvisioner.validateOrigin(origin));
	}

	@Test
	public void rejectsExistingRuntimeAndInterruptedInitializationWithoutReplacement() throws Exception {
		Path secrets = directory("secrets");
		Path password = password(secrets);
		Path existing = directory("existing");
		Path data = existing.resolve("existing-data");
		Files.writeString(data, "preserve-me");
		rejected(existing, password, "https://intruvia.example.org");
		assertEquals("preserve-me", Files.readString(data));
		assertEquals(CANARY, Files.readString(password));
		assertFalse(Files.exists(existing.resolve(FirstStartProvisioner.INITIALIZING)));

		Path partial = directory("partial");
		Path marker = partial.resolve(FirstStartProvisioner.INITIALIZING);
		Files.writeString(marker, "interrupted");
		Files.setPosixFilePermissions(marker, RuntimeLock.FILE_PERMISSIONS);
		rejected(partial, password, "https://intruvia.example.org");
		assertEquals("interrupted", Files.readString(marker));
		assertFalse(Files.exists(partial.resolve(FirstStartProvisioner.INITIALIZED)));
		assertEquals(CANARY, Files.readString(password));
	}

	@Test
	public void rejectsMissingOrUnsafePasswordAndInvalidOriginBeforeInitialization() throws Exception {
		Path secrets = directory("secrets");
		Path password = password(secrets);
		Path invalidOrigin = directory("invalid-origin");
		rejected(invalidOrigin, password, "http://localhost");
		assertFalse(Files.exists(invalidOrigin.resolve(FirstStartProvisioner.INITIALIZING)));
		Path unsafe = directory("unsafe");
		Files.setPosixFilePermissions(password, PosixFilePermissions.fromString("rw-r--r--"));
		rejected(unsafe, password, "https://intruvia.example.org");
		assertFalse(Files.exists(unsafe.resolve(FirstStartProvisioner.INITIALIZING)));
		Files.setPosixFilePermissions(password, RuntimeLock.FILE_PERMISSIONS);
		Files.delete(password);
		Path missing = directory("missing");
		rejected(missing, password, "https://intruvia.example.org");
		assertFalse(Files.exists(missing.resolve(FirstStartProvisioner.INITIALIZING)));
	}

	@Test
	public void refusesExternalXmlEntities() {
		byte[] xml = "<!DOCTYPE x [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><x>&xxe;</x>".getBytes(StandardCharsets.UTF_8);
		assertThrows(Exception.class, () -> ProvisioningFiles.xml(xml));
	}

	private void rejected(Path runtime, Path password, String origin) throws Exception {
		try (var lock = RuntimeLock.acquire(runtime)) {
			var failure = assertThrows(ConfigurationFailure.class, () -> FirstStartProvisioner.ensureInitialized(lock, runtime,
					Path.of("unused-templates"), password, origin, Map.of(), Path.of("unused.jar")));
			assertFalse(failure.getMessage().contains(CANARY));
		}
	}

	private Path directory(String name) throws Exception {
		Path path = temporary.newFolder(name).toPath();
		Files.setPosixFilePermissions(path, RuntimeLock.DIRECTORY_PERMISSIONS);
		return path;
	}

	private static Path password(Path directory) throws Exception {
		Path path = directory.resolve("viewer-password");
		Files.writeString(path, CANARY);
		Files.setPosixFilePermissions(path, RuntimeLock.FILE_PERMISSIONS);
		return path;
	}
}