// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest;

import li.intruvia.core.auth.MachineIdentities;
import li.intruvia.rest.auth.MachineAuthenticationFilter;
import li.strolch.privilege.handler.PrivilegeHandler;
import org.glassfish.jersey.server.ResourceConfig;

import java.util.function.Supplier;

public class IntruviaRestApplication extends ResourceConfig {
	public IntruviaRestApplication() {
		property("jersey.config.server.wadl.disableWadl", true);
		register(HealthResource.class);
	}

	public IntruviaRestApplication(Supplier<PrivilegeHandler> handler, MachineIdentities identities) {
		this();
		register(new MachineAuthenticationFilter(handler, identities));
	}
}
