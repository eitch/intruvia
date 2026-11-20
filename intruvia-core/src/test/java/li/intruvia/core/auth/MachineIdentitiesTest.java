// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.auth;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class MachineIdentitiesTest {
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void changesAreLiveAtomicAndPersistedWithOwnerOnlyPermissions() throws Exception {
		Path file = temporary.newFolder().toPath().resolve("machine-identities.conf");
		Files.createFile(file);
		Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
		MachineIdentities identities = MachineIdentities.load(file);

		identities.setInstance("producer", "server-a");
		assertEquals(Map.of("producer", "server-a"), identities.instances());
		assertEquals("producer=server-a\n", Files.readString(file));
		assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file));
		assertEquals(Map.of("producer", "server-a"), MachineIdentities.load(file).instances());

		assertThrows(IllegalArgumentException.class, () -> identities.setInstance("other", "server-a"));
		assertEquals(Map.of("producer", "server-a"), identities.instances());
		assertTrue(identities.remove("producer"));
		assertTrue(identities.instances().isEmpty());
		assertTrue(Files.readString(file).isEmpty());
		assertTrue(MachineIdentities.load(file).instances().isEmpty());
	}

	@Test
	public void externalEditsAreNotOverwritten() throws Exception {
		Path file = temporary.newFolder().toPath().resolve("machine-identities.conf");
		Files.createFile(file);
		Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
		MachineIdentities identities = MachineIdentities.load(file);
		Files.writeString(file, "external=server-b\n");

		assertThrows(IOException.class, () -> identities.setInstance("producer", "server-a"));
		assertTrue(identities.instances().isEmpty());
		assertEquals("external=server-b\n", Files.readString(file));
	}
}
