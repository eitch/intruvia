// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.*;

public class FirstStartProvisionerTest {
	private static final String CANARY = "legacy-viewer-test-canary";
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
		rejected(existing, "https://intruvia.example.org");
		assertEquals("preserve-me", Files.readString(data));
		assertEquals(CANARY, Files.readString(password));
		assertFalse(Files.exists(existing.resolve(FirstStartProvisioner.INITIALIZING)));

		Path partial = directory("partial");
		Path marker = partial.resolve(FirstStartProvisioner.INITIALIZING);
		Files.writeString(marker, "interrupted");
		Files.setPosixFilePermissions(marker, RuntimeLock.FILE_PERMISSIONS);
		rejected(partial, "https://intruvia.example.org");
		assertEquals("interrupted", Files.readString(marker));
		assertFalse(Files.exists(partial.resolve(FirstStartProvisioner.INITIALIZED)));
		assertEquals(CANARY, Files.readString(password));
	}

	@Test
	public void rejectsInvalidOriginBeforeInitializationAndGeneratesHighEntropyPassword() throws Exception {
		Path secrets = directory("secrets");
		Path password = password(secrets);
		Path invalidOrigin = directory("invalid-origin");
		rejected(invalidOrigin, "http://localhost");
		assertFalse(Files.exists(invalidOrigin.resolve(FirstStartProvisioner.INITIALIZING)));
		assertEquals(CANARY, Files.readString(password));
		char[] generated = FirstStartProvisioner.generateViewerPassword();
		try {
			assertEquals(64, generated.length);
			assertTrue(new String(generated).matches("[0-9a-f]{64}"));
		} finally {
			Arrays.fill(generated, '\0');
		}
	}

	@Test
	public void replacesOnlyTheUniqueViewerPasswordAndSerializesXmlSafely() throws Exception {
		String original = "<Users><User userId=\"viewer\" username=\"viewer\" password=\"old-hash\">"
				+ "<Roles><Role>Viewer</Role></Roles></User><User userId=\"operator\" username=\"operator\" "
				+ "password=\"keep-hash\"><Name>System &amp; Operations</Name></User></Users>";
		byte[] replacement = FirstStartProvisioner.replaceViewerPassword(original.getBytes(StandardCharsets.UTF_8), "new&hash");
		String serialized = new String(replacement, StandardCharsets.UTF_8);
		assertTrue(serialized.contains("password=\"new&amp;hash\""));
		var users = ProvisioningFiles.xml(replacement).getElementsByTagName("User");
		assertEquals("new&hash", ((org.w3c.dom.Element) users.item(0)).getAttribute("password"));
		assertEquals("keep-hash", ((org.w3c.dom.Element) users.item(1)).getAttribute("password"));
		assertTrue(serialized.contains("System &amp; Operations"));
	}

	@Test
	public void rejectsMissingAmbiguousOrMalformedViewerRecords() {
		byte[] missing = "<Users><User userId=\"operator\" username=\"operator\" password=\"hash\"/></Users>"
				.getBytes(StandardCharsets.UTF_8);
		byte[] ambiguous = ("<Users><User userId=\"viewer\" username=\"viewer\" password=\"a\"/>"
				+ "<User userId=\"viewer\" username=\"viewer\" password=\"b\"/></Users>")
				.getBytes(StandardCharsets.UTF_8);
		byte[] malformed = "<Users><User userId=\"viewer\" username=\"other\" password=\"a\"/></Users>"
				.getBytes(StandardCharsets.UTF_8);
		assertThrows(ConfigurationFailure.class, () -> FirstStartProvisioner.replaceViewerPassword(missing, "new-hash"));
		assertThrows(ConfigurationFailure.class, () -> FirstStartProvisioner.replaceViewerPassword(ambiguous, "new-hash"));
		assertThrows(ConfigurationFailure.class, () -> FirstStartProvisioner.replaceViewerPassword(malformed, "new-hash"));
	}

	@Test
	public void refusesExternalXmlEntities() {
		byte[] xml = "<!DOCTYPE x [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><x>&xxe;</x>".getBytes(StandardCharsets.UTF_8);
		assertThrows(Exception.class, () -> ProvisioningFiles.xml(xml));
	}

	private void rejected(Path runtime, String origin) throws Exception {
		try (var lock = RuntimeLock.acquire(runtime)) {
			var failure = assertThrows(ConfigurationFailure.class, () -> FirstStartProvisioner.ensureInitialized(lock, runtime,
					Path.of("unused-templates"), origin, Map.of(), Path.of("unused.jar")));
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