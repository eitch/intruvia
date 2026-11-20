// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.ingest;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.function.Function;

import static org.junit.Assert.*;

public class Fail2banInstallResourceTest {
	private static final String BOOTSTRAP = "__INTRUVIA_INSTALLER_B64__ __INTRUVIA_ACTION_B64__ __INTRUVIA_HELPER_B64__";
	private static final String DIRECTORY = "/li/intruvia/rest/ingest/fail2ban/";

	@Test
	public void embedsExactPackagedBytesAndPreservesBootstrap() throws Exception {
		String[] files = {"install.py", "intruvia.py", "intruvia_notify.py"};
		String[] markers = {"__INTRUVIA_INSTALLER_B64__", "__INTRUVIA_ACTION_B64__", "__INTRUVIA_HELPER_B64__"};
		byte[] bootstrap = resource("download-install.py");
		assertArrayEquals(Files.readAllBytes(Path.of("../integrations/fail2ban/download-install.py")), bootstrap);
		String expected = new String(bootstrap, StandardCharsets.UTF_8);
		for (int i = 0; i < files.length; i++) {
			byte[] bytes = resource(files[i]);
			assertArrayEquals(Files.readAllBytes(Path.of("../integrations/fail2ban", files[i])), bytes);
			expected = expected.replace(markers[i], Base64.getEncoder().encodeToString(bytes));
		}
		assertEquals(expected, new String(Fail2banInstallResource.assemble(
				name -> getClass().getResourceAsStream(DIRECTORY + name)), StandardCharsets.UTF_8));
		var response = new Fail2banInstallResource().download();
		assertEquals(200, response.getStatus());
		assertEquals("no-store", response.getHeaderString("Cache-Control"));
		assertEquals("nosniff", response.getHeaderString("X-Content-Type-Options"));
		assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), (byte[]) response.getEntity());
	}

	@Test
	public void missingEmptyUnreadableAndAmbiguousAssetsFailClosed() throws Exception {
		var assets = Map.of("download-install.py", BOOTSTRAP, "install.py", "installer", "intruvia.py", "action",
				"intruvia_notify.py", "helper");
		for (String missing : assets.keySet()) {
			assertThrows(IOException.class, () -> Fail2banInstallResource.assemble(name ->
					name.equals(missing) ? null : stream(assets.get(name))));
			assertThrows(IOException.class, () -> Fail2banInstallResource.assemble(name ->
					stream(name.equals(missing) ? "" : assets.get(name))));
		}
		for (String marker : BOOTSTRAP.split(" ")) {
			for (String changed : new String[]{BOOTSTRAP.replace(marker, ""), BOOTSTRAP + marker}) {
				assertThrows(IOException.class, () -> Fail2banInstallResource.assemble(name ->
						stream(name.equals("download-install.py") ? changed : assets.get(name))));
			}
		}
		Function<String, InputStream> broken = name -> new InputStream() {
			@Override public int read() throws IOException { throw new IOException("sensitive"); }
		};
		assertThrows(IOException.class, () -> Fail2banInstallResource.assemble(broken));
		var response = new Fail2banInstallResource(broken).download();
		assertEquals(503, response.getStatus());
		assertEquals("no-store", response.getHeaderString("Cache-Control"));
		assertEquals("nosniff", response.getHeaderString("X-Content-Type-Options"));
		assertFalse(response.getEntity().toString().contains("sensitive"));
	}

	private byte[] resource(String name) throws IOException {
		try (InputStream input = getClass().getResourceAsStream(DIRECTORY + name)) {
			assertNotNull(name, input);
			return input.readAllBytes();
		}
	}

	private static InputStream stream(String value) {
		return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
	}
}