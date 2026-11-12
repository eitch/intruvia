// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest;

import li.intruvia.core.auth.MachineIdentities;
import li.intruvia.rest.auth.MachineAuthenticationFilter;
import li.intruvia.rest.ingest.Fail2banResource;
import li.intruvia.rest.ingest.GlobalAdmissionFilter;
import li.intruvia.rest.ingest.IngestExceptionMapper;
import li.intruvia.rest.ingest.IngestionLimits;
import li.intruvia.rest.read.EventsResource;
import li.intruvia.rest.session.SessionResource;
import li.intruvia.rest.session.ViewerAuthenticationFilter;
import li.intruvia.rest.session.ViewerException;
import li.intruvia.rest.session.ViewerSessions;
import li.strolch.privilege.handler.PrivilegeHandler;
import li.strolch.service.api.ServiceHandler;
import org.glassfish.jersey.server.ResourceConfig;

import java.util.function.Supplier;

public class IntruviaRestApplication extends ResourceConfig {
	public IntruviaRestApplication() {
		property("jersey.config.server.wadl.disableWadl", true);
	}

	public IntruviaRestApplication(Supplier<PrivilegeHandler> handler, MachineIdentities identities) {
		this();
		register(new MachineAuthenticationFilter(handler, identities));
	}

	public IntruviaRestApplication(Supplier<PrivilegeHandler> handler, MachineIdentities identities,
			Supplier<ServiceHandler> services, IngestionLimits limits) {
		this(handler, identities, services, limits,
				new li.intruvia.rest.ingest.TokenBucket(limits.globalPerSecond(), limits.globalBurst()));
	}

	public IntruviaRestApplication(Supplier<PrivilegeHandler> handler, MachineIdentities identities,
			Supplier<ServiceHandler> services, IngestionLimits limits, li.intruvia.rest.ingest.TokenBucket globalAdmission) {
		this(handler, identities);
		register(new GlobalAdmissionFilter(globalAdmission));
		register(new Fail2banResource(services, identities, limits));
		register(new EventsResource(services));
		register(IngestExceptionMapper.class);
	}

	public IntruviaRestApplication viewers(ViewerSessions sessions) {
		register(new SessionResource(sessions));
		register(new ViewerAuthenticationFilter(sessions));
		register(ViewerException.Mapper.class);
		return this;
	}
}
