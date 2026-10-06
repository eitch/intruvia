// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.session;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

/** An explicit public origin avoids trusting client-supplied Host or forwarded headers. */
public record ViewerConfiguration(String origin) {
	public ViewerConfiguration {
		if (origin != null) {
			URI uri;
			try {
				uri = URI.create(origin);
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException("Invalid viewer origin");
			}
			boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]").contains(String.valueOf(uri.getHost()));
			if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null ||
					!uri.getRawPath().isEmpty() || uri.getPort() == 0 || uri.getPort() > 65535 ||
					!("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback)))
				throw new IllegalArgumentException("Viewer origin must be an HTTPS origin (HTTP allowed only on loopback)");
		}
	}

	public boolean secure() { return this.origin == null || this.origin.startsWith("https:"); }

	public static ViewerConfiguration load(Path path) throws IOException {
		if (!Files.exists(path))
			return new ViewerConfiguration(null);
		Properties properties = new Properties();
		try (var reader = Files.newBufferedReader(path)) {
			properties.load(reader);
		}
		if (!properties.stringPropertyNames().equals(Set.of("origin")))
			throw new IllegalArgumentException("viewer.properties requires only origin");
		return new ViewerConfiguration(properties.getProperty("origin"));
	}
}
