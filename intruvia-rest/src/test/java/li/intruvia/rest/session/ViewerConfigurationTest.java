// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.session;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;

public class ViewerConfigurationTest {
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void validatesExplicitOriginAndSafeCookieMode() throws Exception {
		for (String origin : List.of("https://intruvia.example.org", "https://intruvia.example.org:8443",
				"http://localhost:8080", "http://127.0.0.1:8080", "http://[::1]:8080")) {
			var configuration = new ViewerConfiguration(origin);
			assertEquals(origin.startsWith("https:"), configuration.secure());
		}
		for (String invalid : List.of("", "null", "*", "http://intruvia.example.org", "https://*.example.org",
				"https://host/path", "https://host/", "https://host?query", "https://host#fragment", "https://user@host",
				"https://host:0", "https://host:65536", "http://localhost.evil:8080", "file:///tmp/example"))
			assertThrows(invalid, IllegalArgumentException.class, () -> new ViewerConfiguration(invalid));
		var path = this.temporary.getRoot().toPath().resolve("viewer.properties");
		assertNull(ViewerConfiguration.load(path).origin());
		for (String invalid : List.of("", "origin=https://host\nsecure=false", "origin=", "origins=https://host")) {
			Files.writeString(path, invalid);
			assertThrows(IllegalArgumentException.class, () -> ViewerConfiguration.load(path));
		}
		Files.writeString(path, "origin=https://host\n");
		assertEquals("https://host", ViewerConfiguration.load(path).origin());
	}
}
