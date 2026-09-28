// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest;

import org.glassfish.jersey.server.ResourceConfig;

public class IntruviaRestApplication extends ResourceConfig {
	public IntruviaRestApplication() {
		property("jersey.config.server.wadl.disableWadl", true);
		register(HealthResource.class);
	}
}
