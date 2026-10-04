// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import li.intruvia.core.ingest.Fail2banEvent;
import li.intruvia.core.ingest.InvalidEventException;
import li.intruvia.rest.dto.Fail2banRequest;

/** Call after authentication. Does not own/close the HTTP body stream or apply timestamp-age policy. */
public final class Fail2banRequestParser {
	public static final int DEFAULT_MAXIMUM_BODY_BYTES = 16 * 1024;
	private static final Set<String> REQUIRED = Set.of("schemaVersion", "eventId", "occurredAt", "action", "ip", "jail");
	private final int maximumBodyBytes;

	public Fail2banRequestParser() { this(DEFAULT_MAXIMUM_BODY_BYTES); }

	public Fail2banRequestParser(int maximumBodyBytes) {
		if (maximumBodyBytes <= 0 || maximumBodyBytes == Integer.MAX_VALUE)
			throw new IllegalArgumentException("Body limit must be positive and allow an overflow sentinel");
		this.maximumBodyBytes = maximumBodyBytes;
	}

	public Fail2banEvent parse(InputStream input) throws IOException {
		byte[] body = input.readNBytes(this.maximumBodyBytes + 1);
		if (body.length > this.maximumBodyBytes)
			throw new InvalidEventException(true);
		String json;
		try {
			json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
		} catch (CharacterCodingException e) {
			throw new InvalidEventException(false);
		}
		try (JsonReader reader = new JsonReader(new StringReader(json))) {
			reader.setStrictness(Strictness.STRICT);
			Map<String, Object> fields = new HashMap<>();
			reader.beginObject();
			while (reader.hasNext()) {
				String name = reader.nextName();
				if ((!REQUIRED.contains(name) && !name.equals("failures")) || fields.containsKey(name))
					throw new InvalidEventException(false);
				Object value;
				if (name.equals("schemaVersion") || name.equals("failures")) {
					if (name.equals("failures") && reader.peek() == JsonToken.NULL) {
						reader.nextNull();
						value = null;
					} else {
						if (reader.peek() != JsonToken.NUMBER)
							throw new InvalidEventException(false);
						value = new BigDecimal(reader.nextString()).intValueExact();
					}
				} else {
					if (reader.peek() != JsonToken.STRING)
						throw new InvalidEventException(false);
					value = reader.nextString();
				}
				fields.put(name, value);
			}
			reader.endObject();
			if (reader.peek() != JsonToken.END_DOCUMENT || !fields.keySet().containsAll(REQUIRED))
				throw new InvalidEventException(false);
			Fail2banRequest request = new Fail2banRequest((Integer) fields.get("schemaVersion"), (String) fields.get("eventId"),
					(String) fields.get("occurredAt"), (String) fields.get("action"), (String) fields.get("ip"),
					(String) fields.get("jail"), (Integer) fields.get("failures"));
			return Fail2banEvent.normalize(request.schemaVersion(), request.eventId(), request.occurredAt(), request.action(),
					request.ip(), request.jail(), request.failures());
		} catch (IOException | IllegalArgumentException | IllegalStateException | ArithmeticException e) {
			// Parser diagnostics can contain payload text. Expose only a fixed validation error.
			throw new InvalidEventException(false);
		}
	}
}
