// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.session;

import jakarta.ws.rs.core.HttpHeaders;
import li.strolch.exception.StrolchNotAuthenticatedException;
import li.strolch.privilege.base.PrivilegeException;
import li.strolch.privilege.model.Certificate;
import li.strolch.privilege.model.Usage;
import li.strolch.runtime.sessions.StrolchSessionHandler;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/** Shared REST/future WebSocket validation. No cached authorization or parallel session/token registry. */
public final class ViewerSessions {
	public static final String COOKIE = "IntruviaSession";
	public static final String READ = "event:read";
	private static final String SOURCE = "intruvia-viewer";
	private final Supplier<StrolchSessionHandler> handler;
	private final ViewerConfiguration configuration;
	private final Clock clock;

	public ViewerSessions(Supplier<StrolchSessionHandler> handler, ViewerConfiguration configuration, Clock clock) {
		this.handler = handler;
		this.configuration = configuration;
		this.clock = clock;
	}

	public ViewerConfiguration configuration() { return this.configuration; }

	/** Mutations and WebSocket upgrades require Origin; ordinary same-origin GET fetches may omit it. */
	public void checkOrigin(List<String> origins, boolean required) {
		if (this.configuration.origin() == null)
			throw ViewerHttp.failure(503);
		if (origins == null || origins.isEmpty()) {
			if (required)
				throw ViewerHttp.failure(403);
		} else if (origins.size() != 1 || !this.configuration.origin().equals(origins.getFirst())) {
			throw ViewerHttp.failure(403);
		}
	}

	public void checkRequest(HttpHeaders headers, boolean mutation) {
		checkOrigin(headers.getRequestHeader("Origin"), mutation);
		if (headers.getRequestHeader(HttpHeaders.AUTHORIZATION) != null)
			throw ViewerHttp.failure(401);
		var site = headers.getRequestHeader("Sec-Fetch-Site");
		if (site != null && (site.size() != 1 || !site.getFirst().equals("same-origin")))
			throw ViewerHttp.failure(403);
		// A custom header cannot be sent by a cross-origin form. No CORS/preflight permission is exposed.
		if (mutation && !List.of("1").equals(headers.getRequestHeader("X-Intruvia-CSRF")))
			throw ViewerHttp.failure(403);
	}

	public Certificate login(String username, char[] password) {
		Certificate certificate;
		try {
			certificate = this.handler.get().authenticate(username, password, SOURCE, Usage.ANY, false);
		} catch (PrivilegeException e) {
			throw ViewerHttp.failure(401);
		} finally {
			Arrays.fill(password, '\0');
		}
		try {
			return authorize(certificate);
		} catch (jakarta.ws.rs.WebApplicationException e) {
			this.handler.get().invalidate(certificate);
			throw e;
		}
	}

	public Certificate validate(HttpHeaders headers) {
		checkRequest(headers, false);
		return validateToken(cookie(headers));
	}

	/** Use on a WebSocket upgrade, then revalidate the token during the connection lifetime. */
	public Certificate validateUpgrade(HttpHeaders headers) {
		checkOrigin(headers.getRequestHeader("Origin"), true);
		return validate(headers);
	}

	/** Caller must enforce exact Origin and cookie-only transport before using this for an upgrade. */
	public Certificate validateToken(String token) {
		if (token == null || token.length() > 1024 || !token.matches("[A-Za-z0-9_-]+:[A-Za-z0-9_-]+"))
			throw ViewerHttp.failure(401);
		try {
			return authorize(this.handler.get().validate(token, SOURCE));
		} catch (StrolchNotAuthenticatedException | PrivilegeException e) {
			throw ViewerHttp.failure(401);
		}
	}

	private Certificate authorize(Certificate certificate) {
		if (!certificate.getUsage().isAny() || certificate.getUserState().isSystem())
			throw ViewerHttp.failure(401);
		// Framework idle cleanup is periodic. Enforce a fixed maximum lifetime synchronously on every read/upgrade.
		if (!this.clock.instant().isBefore(certificate.getLoginTime().toInstant()
				.plusSeconds(this.handler.get().getSessionMaxKeepAliveMinutes() * 60L))) {
			this.handler.get().invalidate(certificate);
			throw ViewerHttp.failure(401);
		}
		try {
			if (!this.handler.get().validate(certificate, SOURCE).hasPrivilege(READ, READ))
				throw ViewerHttp.failure(403);
		} catch (StrolchNotAuthenticatedException e) {
			throw ViewerHttp.failure(401);
		} catch (PrivilegeException e) {
			throw ViewerHttp.failure(403);
		}
		return certificate;
	}

	public void logout(Certificate certificate) { this.handler.get().invalidate(certificate); }

	public static String cookie(HttpHeaders headers) {
		String value = null;
		var cookies = headers.getRequestHeader(HttpHeaders.COOKIE);
		if (cookies != null) {
			for (String header : cookies) {
				for (String part : header.split(";")) {
					String[] pair = part.strip().split("=", 2);
					if (pair[0].equals(COOKIE)) {
						if (value != null || pair.length != 2)
							throw ViewerHttp.failure(401);
						value = pair[1];
					}
				}
			}
		}
		return value;
	}
}
