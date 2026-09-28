// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.rest.dto;

import java.util.UUID;
import com.google.gson.JsonParser;
import li.intruvia.core.model.EventJson;
import li.intruvia.core.model.GeoStatus;
import org.junit.Test;
import static org.junit.Assert.*;

public class Fail2banDtoTest {
	@Test
	public void requestOptionalFailuresAndIpv6RoundTrip() {
		String json = """
				{"schemaVersion":1,"eventId":"55c42ae2-865b-47b8-bc4b-c166c0344c5c",
				"occurredAt":"2026-09-28T12:00:00Z","action":"ban","ip":"2001:db8::42","jail":"sshd"}
				""";
		Fail2banRequest request = EventJson.read(json, Fail2banRequest.class);
		assertNull(request.failures());
		assertEquals("2001:db8::42", request.ip());
		assertEquals(request, EventJson.read(EventJson.write(request), Fail2banRequest.class));
	}

	@Test
	public void duplicateAndNewResponsesUseStringSequences() {
		for (boolean duplicate : new boolean[]{false, true}) {
			var response = new Fail2banResponse(UUID.randomUUID(), Long.toString(Long.MAX_VALUE), duplicate, GeoStatus.NON_PUBLIC);
			String json = EventJson.write(response);
			assertTrue(JsonParser.parseString(json).getAsJsonObject().get("sequence").getAsJsonPrimitive().isString());
			assertEquals(response, EventJson.read(json, Fail2banResponse.class));
		}
		var error = new ApiError(new ApiError.Error("invalid_request", "Invalid event", "request-1"));
		assertEquals(error, EventJson.read(EventJson.write(error), ApiError.class));
	}
}
