// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.session;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

final class AdminRequest {
	private AdminRequest() {}

	static Map<String, String> read(HttpHeaders headers, InputStream body, Set<String> allowed) {
		MediaType media = headers.getMediaType();
		if (media == null || !media.isCompatible(MediaType.APPLICATION_JSON_TYPE) ||
				!media.getParameters().getOrDefault("charset", "UTF-8").equalsIgnoreCase("UTF-8") ||
				headers.getHeaderString("Content-Encoding") != null)
			throw ViewerHttp.failure(415);
		try {
			byte[] bytes = body.readNBytes(8193);
			if (bytes.length > 8192)
				throw ViewerHttp.failure(413);
			String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
			try (var reader = new JsonReader(new StringReader(text))) {
				reader.setStrictness(Strictness.STRICT);
				Map<String, String> fields = new HashMap<>();
				reader.beginObject();
				while (reader.hasNext()) {
					String key = reader.nextName();
					JsonToken value = reader.peek();
					if (!allowed.contains(key) || fields.containsKey(key) ||
							(value != JsonToken.STRING && value != JsonToken.NUMBER))
						throw ViewerHttp.failure(400);
					fields.put(key, reader.nextString());
				}
				reader.endObject();
				if (reader.peek() != JsonToken.END_DOCUMENT)
					throw ViewerHttp.failure(400);
				return Map.copyOf(fields);
			}
		} catch (IOException | IllegalArgumentException | IllegalStateException e) {
			throw ViewerHttp.failure(400);
		}
	}

	static void requireFields(Map<String, String> fields, Set<String> required) {
		if (!fields.keySet().containsAll(required))
			throw ViewerHttp.failure(400);
	}

	static String text(Map<String, String> fields, String name, int maximum) {
		String value = fields.get(name);
		if (value == null || value.isBlank() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl))
			throw ViewerHttp.failure(400);
		return value.strip();
	}

	static int days(Map<String, String> fields) {
		String value = fields.get("validDays");
		if (value == null || !value.matches("[0-9]{1,4}"))
			throw ViewerHttp.failure(400);
		try {
			int days = Integer.parseInt(value);
			if (days < 1 || days > 3650)
				throw ViewerHttp.failure(400);
			return days;
		} catch (NumberFormatException e) {
			throw ViewerHttp.failure(400);
		}
	}
}
