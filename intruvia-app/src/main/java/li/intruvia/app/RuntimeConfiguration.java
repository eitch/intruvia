// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.app;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** Validate required files and privilege secrets before the framework opens components. No values in errors. */
final class RuntimeConfiguration {
	private RuntimeConfiguration() {}
	static void validateSessions(li.strolch.runtime.configuration.StrolchConfiguration configuration) {
		var sessions = configuration.getComponentConfiguration("SessionHandler");
		for (String key : new String[]{"session.ttl.minutes", "session.maxKeepAlive.minutes"}) {
			try {
				int minutes = Integer.parseInt(sessions.getAsMap().getOrDefault(key, "30"));
				if (minutes >= 1 && minutes <= 1440) continue;
			} catch (NumberFormatException e) {
				// Fixed setting name only, never the configured value.
			}
			throw new ConfigurationFailure("Invalid SessionHandler setting: " + key + " (1–1440 minutes required)");
		}
	}

	static void validate(Path runtime) {
		for (String name : new String[]{"StrolchConfiguration.xml", "PrivilegeConfig.xml", "PrivilegeRoles.xml", "PrivilegeUsers.xml"})
			if (!Files.isRegularFile(runtime.resolve("config").resolve(name)))
				throw new ConfigurationFailure("Missing required runtime config/" + name);
		try {
			var factory = DocumentBuilderFactory.newInstance();
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			var builder = factory.newDocumentBuilder();
			builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
			var document = builder.parse(runtime.resolve("config/PrivilegeConfig.xml").toFile());
			var parameters = document.getElementsByTagName("Parameter");
			for (String key : new String[]{"secretKey", "secretSalt"}) {
				String value = null;
				for (int index = 0; index < parameters.getLength(); index++) {
					var parameter = (org.w3c.dom.Element) parameters.item(index);
					if (key.equals(parameter.getAttribute("name"))) {
						if (value != null) throw new IllegalArgumentException();
						value = parameter.getAttribute("value");
					}
				}
				if (value == null || value.isBlank() || value.equals("CHANGE-ME"))
					throw new IllegalArgumentException();
			}
		} catch (java.io.IOException | org.xml.sax.SAXException | javax.xml.parsers.ParserConfigurationException | IllegalArgumentException e) {
			throw new ConfigurationFailure("Invalid PrivilegeConfig.xml; configure secretKey and secretSalt");
		}
	}
}
