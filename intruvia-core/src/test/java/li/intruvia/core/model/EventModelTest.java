// SPDX-License-Identifier: AGPL-3.0-only
package li.intruvia.core.model;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonParser;
import li.strolch.model.Resource;
import li.strolch.model.xml.StrolchXmlHelper;
import org.junit.Test;

import static org.junit.Assert.*;

public class EventModelTest {
	private final EventModelMapper mapper = new EventModelMapper();

	private SecurityEvent fixture() throws Exception {
		return EventJson.read(Files.readString(Path.of("../docs/api/event-v1.json")), SecurityEvent.class);
	}

	private Resource xmlRoundTrip(Resource resource) {
		StringWriter writer = new StringWriter();
		StrolchXmlHelper.writeToWriter(writer, List.of(resource));
		var input = new ByteArrayInputStream(writer.toString().getBytes(StandardCharsets.UTF_8));
		return (Resource) StrolchXmlHelper.parseInputStreamAsList(input, "UTF-8").getFirst();
	}

	@Test
	public void completeFixturePreservesIpv6NamespacesPrecisionAndZeros() throws Exception {
		SecurityEvent event = fixture();
		Resource resource = this.mapper.toResource(event);
		assertEquals(9007199254740993L, resource.getLong("envelope", "sequence"));
		assertEquals("2001:db8::42", resource.getString("subject", "ip"));
		assertEquals(7, resource.getInteger("attributes.fail2ban", "failures"));
		assertEquals(event, this.mapper.toEvent(xmlRoundTrip(resource)));
		assertEquals(event, EventJson.read(EventJson.write(event), SecurityEvent.class));
		assertTrue(JsonParser.parseString(EventJson.write(event)).getAsJsonObject().get("sequence").getAsJsonPrimitive().isString());
		assertEquals(Double.valueOf(0), event.geo().latitude());
		assertEquals("2026-09-28T12:00:00.123456789Z", event.occurredAt());
	}

	@Test
	public void missingOptionalsStayNullForEveryGeoStatus() throws Exception {
		for (GeoStatus status : GeoStatus.values()) {
			var json = JsonParser.parseString(EventJson.write(fixture())).getAsJsonObject();
			json.getAsJsonObject("attributes").getAsJsonObject("fail2ban").remove("failures");
			var geo = new com.google.gson.JsonObject();
			geo.addProperty("status", status.name());
			json.add("geo", geo);
			SecurityEvent event = EventJson.read(json.toString(), SecurityEvent.class);
			Resource resource = this.mapper.toResource(event);
			assertFalse(resource.hasParameter("geo", "latitude"));
			assertFalse(resource.hasParameter("attributes.fail2ban", "failures"));
			SecurityEvent restored = this.mapper.toEvent(xmlRoundTrip(resource));
			assertEquals(event, restored);
			assertNull(restored.geo().latitude());
			assertNull(restored.geo().longitude());
			assertNull(restored.geo().city());
			assertNull(restored.attributes().fail2ban().failures());
			assertEquals(restored, EventJson.read(EventJson.write(restored), SecurityEvent.class));
		}
	}

	@Test
	public void partialLocationAndNamespacedTextRemainUnchanged() throws Exception {
		var json = JsonParser.parseString(EventJson.write(fixture())).getAsJsonObject();
		var geo = json.getAsJsonObject("geo");
		geo.add("latitude", com.google.gson.JsonNull.INSTANCE);
		geo.add("longitude", com.google.gson.JsonNull.INSTANCE);
		geo.remove("accuracyRadiusKm");
		String jail = "<script>example & \"text\"</script>";
		json.getAsJsonObject("attributes").getAsJsonObject("fail2ban").addProperty("jail", jail);
		SecurityEvent event = EventJson.read(json.toString(), SecurityEvent.class);
		SecurityEvent restored = this.mapper.toEvent(xmlRoundTrip(this.mapper.toResource(event)));
		assertEquals(event, restored);
		assertEquals("Synthetic test country", restored.geo().countryName());
		assertEquals(jail, restored.attributes().fail2ban().jail());
		assertNull(restored.geo().latitude());
		assertEquals(event, EventJson.read(EventJson.write(restored), SecurityEvent.class));
	}

	@Test
	public void receiptsAndStreamStatePreserveLongMaximum() {
		String maximum = Long.toString(Long.MAX_VALUE);
		IngestReceipt receipt = new IngestReceipt("receipt-1", "server-1", UUID.randomUUID(), "validated-digest",
				UUID.randomUUID(), maximum, "2026-10-28T14:00:00+02:00");
		assertEquals("2026-10-28T12:00:00Z", receipt.expiresAt());
		assertEquals(receipt, this.mapper.toReceipt(xmlRoundTrip(this.mapper.toResource(receipt))));
		for (EventStreamState state : List.of(new EventStreamState("0", "0"), new EventStreamState(maximum, maximum))) {
			assertEquals(state, this.mapper.toStreamState(xmlRoundTrip(this.mapper.toResource(state))));
			assertEquals(state, EventJson.read(EventJson.write(state), EventStreamState.class));
		}
	}

	@Test
	public void unsupportedVersionAndInvalidModelValuesFailClearly() throws Exception {
		var json = JsonParser.parseString(EventJson.write(fixture())).getAsJsonObject();
		json.addProperty("schemaVersion", 2);
		assertThrows(RuntimeException.class, () -> EventJson.read(json.toString(), SecurityEvent.class));
		Resource resource = this.mapper.toResource(fixture());
		resource.setInteger("envelope", "schemaVersion", 2);
		assertThrows(IllegalArgumentException.class, () -> this.mapper.toEvent(resource));
		for (String invalid : List.of("-1", "01", "1.0", "9223372036854775808"))
			assertThrows(IllegalArgumentException.class, () -> new EventStreamState(invalid, "0"));
		assertThrows(IllegalArgumentException.class, () -> new EventStreamState("1", "2"));
		assertThrows(IllegalArgumentException.class, () -> new SecurityEvent.Fail2ban("x".repeat(129), null));
		assertThrows(IllegalArgumentException.class, () -> new SecurityEvent.Fail2ban("sshd", -1));
		assertThrows(IllegalArgumentException.class, () -> new SecurityEvent.Geo(GeoStatus.FOUND, null, null, null,
				null, 0.0, null, null, null, null, null, null));
	}
}
