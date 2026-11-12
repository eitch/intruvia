// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class RuntimeConfigurationTest {
	@Test public void requiredFilesSecretsAndMalformedXmlFailWithoutEchoingValues() throws Exception {
		Path runtime = Files.createTempDirectory(Path.of("target"), "config-validation-");
		Path config = Files.createDirectories(runtime.resolve("config"));
		assertTrue(assertThrows(IllegalArgumentException.class, () -> RuntimeConfiguration.validate(runtime))
				.getMessage().contains("StrolchConfiguration.xml"));
		for (String file : new String[]{"StrolchConfiguration.xml", "PrivilegeRoles.xml", "PrivilegeUsers.xml"})
			Files.writeString(config.resolve(file), "<test/>");
		for (String content : new String[]{"<Privilege/>", "<Parameter name='secretKey' value='CHANGE-ME'/>",
				"<malformed secret-CANARY", "<!DOCTYPE x SYSTEM 'file:///etc/passwd'><x/>"}) {
			Files.writeString(config.resolve("PrivilegeConfig.xml"), content);
			String error = assertThrows(IllegalArgumentException.class, () -> RuntimeConfiguration.validate(runtime)).getMessage();
			assertFalse(error.contains("CANARY"));
		}
		Files.writeString(config.resolve("PrivilegeConfig.xml"),
				"<Parameters><Parameter name='secretKey' value='synthetic-key'/><Parameter name='secretSalt' value='synthetic-salt'/></Parameters>");
		RuntimeConfiguration.validate(runtime);
	}
}
