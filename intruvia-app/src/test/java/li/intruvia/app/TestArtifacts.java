// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import java.nio.file.Files;
import java.nio.file.Path;

final class TestArtifacts {
	private TestArtifacts() {}

	static Path applicationJar() {
		String version = System.getProperty("intruvia.application.version");
		if (version == null || version.isBlank())
			throw new IllegalStateException("Failsafe did not provide the Intruvia application version");
		Path jar = Path.of("target/intruvia", "intruvia-app-" + version + ".jar").toAbsolutePath().normalize();
		if (!Files.isRegularFile(jar)) throw new IllegalStateException("Packaged application JAR not found: " + jar);
		return jar;
	}
}