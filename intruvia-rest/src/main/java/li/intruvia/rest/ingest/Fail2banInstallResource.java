// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.ingest;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import li.intruvia.rest.auth.MachineIngest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Function;

@Path("fail2ban/install.py")
@MachineIngest
public final class Fail2banInstallResource {

	private static final String ASSETS = "/li/intruvia/rest/ingest/fail2ban/";
	private final Function<String, InputStream> assets;

	public Fail2banInstallResource() {
		this(name -> Fail2banInstallResource.class.getResourceAsStream(ASSETS + name));
	}

	Fail2banInstallResource(Function<String, InputStream> assets) {
		this.assets = assets;
	}

	@GET
	public Response download() {
		try {
			byte[] installer = assemble(this.assets);
			return Response.ok(installer, "text/x-python; charset=UTF-8").header("Cache-Control", "no-store")
					.header("X-Content-Type-Options", "nosniff").build();
		} catch (IOException e) {
			return IngestHttp.error(503, "unavailable", "Installer unavailable");
		}
	}

	static byte[] assemble(Function<String, InputStream> assets) throws IOException {
		String bootstrap = new String(read(assets, "download-install.py"), StandardCharsets.UTF_8);
		bootstrap = replace(bootstrap, "__INTRUVIA_INSTALLER_B64__", read(assets, "install.py"));
		bootstrap = replace(bootstrap, "__INTRUVIA_ACTION_B64__", read(assets, "intruvia.py"));
		bootstrap = replace(bootstrap, "__INTRUVIA_HELPER_B64__", read(assets, "intruvia_notify.py"));
		return bootstrap.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] read(Function<String, InputStream> assets, String name) throws IOException {
		try (InputStream input = assets.apply(name)) {
			if (input == null)
				throw new IOException("Installer asset unavailable");
			byte[] bytes = input.readAllBytes();
			if (bytes.length == 0)
				throw new IOException("Installer asset empty");
			return bytes;
		}
	}

	private static String replace(String bootstrap, String marker, byte[] bytes) throws IOException {
		int first = bootstrap.indexOf(marker);
		if (first < 0 || first != bootstrap.lastIndexOf(marker))
			throw new IOException("Installer marker invalid");
		return bootstrap.replace(marker, Base64.getEncoder().encodeToString(bytes));
	}
}