// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.auth;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import li.intruvia.rest.ingest.IngestHttp;
import jakarta.ws.rs.core.SecurityContext;
import li.intruvia.core.auth.MachineIdentities;
import li.strolch.privilege.base.AccessDeniedException;
import li.strolch.privilege.handler.PrivilegeHandler;
import li.strolch.privilege.model.Certificate;

import java.security.Principal;
import java.util.Set;
import java.util.function.Supplier;

@MachineIngest
@Priority(Priorities.AUTHENTICATION)
public final class MachineAuthenticationFilter implements ContainerRequestFilter {
	public static final String INGEST = "event:ingest";
	private final Supplier<PrivilegeHandler> handler;
	private final MachineIdentities identities;

	public MachineAuthenticationFilter(Supplier<PrivilegeHandler> handler, MachineIdentities identities) {
		this.handler = handler;
		this.identities = identities;
	}

	@Override
	public void filter(ContainerRequestContext request) {
		var headers = request.getHeaders().get("Authorization");
		String header = headers != null && headers.size() == 1 ? headers.getFirst() : null;
		String token = header != null && header.length() <= 512 && header.regionMatches(true, 0, "Bearer ", 0, 7)
				? header.substring(7) : null;
		// Bound and validate the framework wire shape before invoking its verifier. Never accept a session ID.
		if (token == null || !token.matches("[A-Za-z0-9-]+:[A-Za-z0-9_+/=-]+")) {
			reject(request, 401, "unauthorized", "Authentication required");
			return;
		}
		PrivilegeHandler privileges = this.handler.get();
		Certificate certificate;
		li.strolch.privilege.model.PrivilegeContext context;
		try {
			certificate = privileges.authenticatePersonalAccessToken(token, "intruvia-ingest");
			context = privileges.validate(certificate);
		} catch (AccessDeniedException e) {
			reject(request, 401, "unauthorized", "Authentication required");
			return;
		}
		String instance = this.identities.instances().get(certificate.getUsername());
		// The ingestion service uses this same privilege. Broad/admin certificates are never accepted here.
		if (!certificate.getUsage().isApi() || instance == null ||
				!context.getPrivileges().keySet().equals(Set.of(INGEST)) || !context.hasPrivilege(INGEST, INGEST)) {
			reject(request, 403, "forbidden", "Ingestion permission and server identity required");
			return;
		}
		Identity identity = new Identity(instance, certificate);
		boolean secure = request.getSecurityContext().isSecure();
		request.setSecurityContext(new SecurityContext() {
			@Override public Principal getUserPrincipal() { return identity; }
			@Override public boolean isUserInRole(String role) { return INGEST.equals(role); }
			@Override public boolean isSecure() { return secure; }
			@Override public String getAuthenticationScheme() { return "Bearer"; }
		});
	}

	/** The service layer receives the framework certificate, never a fabricated authentication identity. */
	public record Identity(String instanceId, Certificate certificate) implements Principal {
		@Override public String getName() { return this.instanceId; }
	}

	private static void reject(ContainerRequestContext request, int status, String code, String message) {
		request.abortWith(IngestHttp.error(status, code, message));
	}
}
