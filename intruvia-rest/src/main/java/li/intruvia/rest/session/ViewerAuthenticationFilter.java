// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.session;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.SecurityContext;
import li.strolch.privilege.model.Certificate;
import java.security.Principal;

@ViewerRead
@Priority(Priorities.AUTHENTICATION)
public final class ViewerAuthenticationFilter implements ContainerRequestFilter {
	private final ViewerSessions sessions;
	@Context private HttpHeaders headers;

	public ViewerAuthenticationFilter(ViewerSessions sessions) { this.sessions = sessions; }

	public record Identity(Certificate certificate) implements Principal {
		@Override public String getName() { return this.certificate.getUsername(); }
	}

	@Override
	public void filter(ContainerRequestContext request) {
		Certificate certificate = this.sessions.validate(this.headers);
		request.setSecurityContext(new SecurityContext() {
			@Override public Principal getUserPrincipal() { return new Identity(certificate); }
			@Override public boolean isUserInRole(String role) { return ViewerSessions.READ.equals(role); }
			@Override public boolean isSecure() { return sessions.configuration().secure(); }
			@Override public String getAuthenticationScheme() { return "COOKIE"; }
		});
	}
}
