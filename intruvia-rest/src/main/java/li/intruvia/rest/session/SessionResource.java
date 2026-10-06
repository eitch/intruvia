// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.session;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import li.intruvia.rest.ingest.IngestHttp;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Path("session")
public final class SessionResource {
	private final ViewerSessions sessions;

	public SessionResource(ViewerSessions sessions) { this.sessions = sessions; }

	@POST
	public Response login(@Context HttpHeaders headers, InputStream body) {
		this.sessions.checkRequest(headers, true);
		MediaType media = headers.getMediaType();
		if (media == null || !media.isCompatible(MediaType.APPLICATION_JSON_TYPE) ||
				!media.getParameters().getOrDefault("charset", "UTF-8").equalsIgnoreCase("UTF-8") ||
				headers.getHeaderString("Content-Encoding") != null)
			throw ViewerHttp.failure(415);
		Map<String, String> fields = credentials(body);
		// Rotate by invalidating an existing authenticated session; never reuse a caller-supplied ID.
		String old = ViewerSessions.cookie(headers);
		if (old != null) {
			try {
				this.sessions.logout(this.sessions.validateToken(old));
			} catch (ViewerException e) {
				if (e.getResponse().getStatus() != 401 && e.getResponse().getStatus() != 403)
					throw e;
			}
		}
		var certificate = this.sessions.login(fields.get("username"), fields.get("password").toCharArray());
		return Response.fromResponse(IngestHttp.json(200, Map.of("username", certificate.getUsername())))
				.cookie(cookie(certificate.getAuthToken(), false)).build();
	}

	@GET
	@ViewerRead
	public Response current(@Context SecurityContext context) {
		return IngestHttp.json(200, Map.of("username", context.getUserPrincipal().getName()));
	}

	@DELETE
	public Response logout(@Context HttpHeaders headers) {
		this.sessions.checkRequest(headers, true);
		this.sessions.logout(this.sessions.validate(headers));
		return Response.noContent().header("Cache-Control", "no-store").cookie(cookie("", true)).build();
	}

	private NewCookie cookie(String value, boolean clear) {
		return new NewCookie.Builder(ViewerSessions.COOKIE).value(value).path("/api/v1")
				.httpOnly(true).secure(this.sessions.configuration().secure()).sameSite(NewCookie.SameSite.STRICT)
				.maxAge(clear ? 0 : NewCookie.DEFAULT_MAX_AGE).build();
	}

	private static Map<String, String> credentials(InputStream body) {
		try {
			byte[] bytes = body.readNBytes(4097);
			if (bytes.length > 4096)
				throw ViewerHttp.failure(413);
			String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
			try (var reader = new JsonReader(new StringReader(text))) {
				reader.setStrictness(Strictness.STRICT);
				Map<String, String> fields = new HashMap<>();
				reader.beginObject();
				while (reader.hasNext()) {
					String key = reader.nextName();
					if (!Set.of("username", "password").contains(key) || fields.containsKey(key) || reader.peek() != JsonToken.STRING)
						throw ViewerHttp.failure(400);
					fields.put(key, reader.nextString());
				}
				reader.endObject();
				if (reader.peek() != JsonToken.END_DOCUMENT || !fields.keySet().equals(Set.of("username", "password")) ||
						fields.get("username").isBlank() || fields.get("username").length() > 128 ||
						fields.get("password").isEmpty() || fields.get("password").length() > 1024)
					throw ViewerHttp.failure(400);
				return fields;
			}
		} catch (IOException | IllegalArgumentException | IllegalStateException e) {
			throw ViewerHttp.failure(400);
		}
	}
}
